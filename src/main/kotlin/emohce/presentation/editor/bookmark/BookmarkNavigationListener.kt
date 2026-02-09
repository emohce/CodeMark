package emohce.presentation.editor.bookmark

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import emohce.presentation.toolwindow.BookmarkIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 监听编辑器选择变化，当选择到书签所在行时，自动打开工具窗口并定位到对应节点
 */
class BookmarkNavigationListener(private val project: Project) {
    private val logger = Logger.getInstance(BookmarkNavigationListener::class.java)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val lastHandledPosition = ConcurrentHashMap<String, Int>() // filePath -> line
    private val pendingNavigation = ConcurrentHashMap<String, String>() // filePath:line -> uuid
    private val attachedListeners = ConcurrentHashMap<Editor, BookmarkCaretListener>() // 必须在 init 之前初始化
    
    init {
        logger.info("[BOOKMARK_NAV] Initializing BookmarkNavigationListener")
        setupEditorListener()
        logger.info("[BOOKMARK_NAV] BookmarkNavigationListener initialized")
    }
    
    private fun setupEditorListener() {
        logger.info("[BOOKMARK_NAV] Setting up editor listeners")
        // 监听编辑器选择变化
        val fileEditorManager = FileEditorManager.getInstance(project)
        fileEditorManager.addFileEditorManagerListener(object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                attachCaretListener(file)
            }
            
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                // 清理
                val filePath = FileUtil.toSystemIndependentName(file.path)
                lastHandledPosition.remove(filePath)
            }
            
            override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                // 仅 gutter 点击驱动工具窗口选中；切换文件/标签页不再自动打开工具窗口
            }
        })
        
        // 为已打开的文件附加监听器
        val openFiles = fileEditorManager.openFiles
        logger.info("[BOOKMARK_NAV] Found ${openFiles.size} open files, attaching listeners")
        openFiles.forEach { file ->
            attachCaretListener(file)
        }
        logger.info("[BOOKMARK_NAV] Editor listeners setup completed")
    }
    
    private fun attachCaretListener(file: VirtualFile) {
        val filePath = FileUtil.toSystemIndependentName(file.path)
        val editors = FileEditorManager.getInstance(project).getEditors(file)
        
        logger.info("[BOOKMARK_NAV] Attaching caret listener to file: $filePath, editors count=${editors.size}")
        
        editors.forEach { editor ->
            if (editor is com.intellij.openapi.fileEditor.TextEditor) {
                val textEditor = editor.editor
                // 检查是否已经添加了监听器
                if (!attachedListeners.containsKey(textEditor)) {
                    val listener = BookmarkCaretListener(filePath)
                    textEditor.caretModel.addCaretListener(listener)
                    attachedListeners[textEditor] = listener
                    logger.info("[BOOKMARK_NAV] Caret listener attached to editor for file: $filePath")
                } else {
                    logger.debug("[BOOKMARK_NAV] Caret listener already attached for file: $filePath")
                }
            }
        }
    }
    
    private inner class BookmarkCaretListener(private val filePath: String) : CaretListener {
        private var lastLine: Int? = null
        private var lastChangeTime: Long = 0
        
        override fun caretPositionChanged(event: CaretEvent) {
            val editor = event.editor
            val line = editor.caretModel.primaryCaret.logicalPosition.line
            val currentTime = System.currentTimeMillis()
            
            logger.info("[BOOKMARK_NAV] Caret position changed: filePath=$filePath, line=$line, lastLine=$lastLine, timeSinceLastChange=${if (lastLine != null) currentTime - lastChangeTime else -1}ms")
            
            // 检查是否已经处理过这个位置（避免重复触发）
            val cachedLastLine = lastHandledPosition[filePath]
            if (cachedLastLine == line) {
                logger.debug("[BOOKMARK_NAV] Already handled this position, skipping: filePath=$filePath, line=$line")
                return
            }
            
            // 仅处理待办导航（如从面板“定位到行”后等光标到达再选中节点）；不因单纯光标移动打开工具窗口
            val key = "$filePath:$line"
            val uuid = pendingNavigation.remove(key)
            if (uuid != null) {
                logger.info("[BOOKMARK_NAV] Handling pending navigation: filePath=$filePath, line=$line, uuid=$uuid")
                handleBookmarkNavigation(uuid)
                lastHandledPosition[filePath] = line
                lastLine = line
                lastChangeTime = currentTime
                return
            }
            
            lastLine = line
            lastChangeTime = currentTime
        }
    }
    
    /**
     * 根据文件和行号查找书签
     */
    private suspend fun findBookmarkByFileAndLine(filePath: String, line: Int): BookmarkNode.Bookmark? {
        return withContext(Dispatchers.IO) {
            val locator = ServiceLocator.get(project)
            val root = locator.bookmarkRepository.getRootNode()
            val normalizedPath = FileUtil.toSystemIndependentName(filePath)
            
            var result: BookmarkNode.Bookmark? = null
            traverse(root) { node ->
                if (node is BookmarkNode.Bookmark) {
                    val nodePath = FileUtil.toSystemIndependentName(node.filePath)
                    if (nodePath == normalizedPath && node.line == line) {
                        result = node
                    }
                }
            }
            result
        }
    }
    
    /**
     * 处理书签导航
     */
    private fun handleBookmarkNavigation(uuid: String) {
        scope.launch {
            try {
                logger.info("[BOOKMARK_NAV] Opening tool window and selecting node: uuid=$uuid")
                
                // 打开工具窗口
                openToolWindow()
                
                // 延迟一下，确保工具窗口已打开
                delay(150)
                
                // 定位到对应节点（这会自动展开路径并聚焦）
                val locator = ServiceLocator.get(project)
                locator.bookmarkViewModel.processIntent(BookmarkIntent.SelectNode(uuid))
                
                logger.info("[BOOKMARK_NAV] SelectNode intent sent for UUID: $uuid")
            } catch (e: Exception) {
                logger.error("[BOOKMARK_NAV] Error handling bookmark navigation: ${e.message}", e)
            }
        }
    }
    
    /**
     * 检查当前行是否有我们的书签，如果有则处理导航
     */
    private fun checkAndHandleBookmarkNavigation(filePath: String, line: Int) {
        scope.launch {
            try {
                logger.info("[BOOKMARK_NAV] Checking bookmark for filePath=$filePath, line=$line")
                
                // 直接通过我们的书签仓库查找
                val bookmark = findBookmarkByFileAndLine(filePath, line)
                
                if (bookmark != null) {
                    logger.info("[BOOKMARK_NAV] Found bookmark: uuid=${bookmark.uuid}, name=${bookmark.name}")
                    handleBookmarkNavigation(bookmark.uuid)
                } else {
                    logger.debug("[BOOKMARK_NAV] No bookmark found at filePath=$filePath, line=$line")
                }
            } catch (e: Exception) {
                logger.error("[BOOKMARK_NAV] Error checking bookmark: ${e.message}", e)
            }
        }
    }
    
    /**
     * 从描述中提取 UUID
     * 格式: "name$uuid"
     */
    private fun extractUuid(description: String?): String? {
        if (description == null) return null
        val parts = description.split('$')
        return if (parts.size >= 2) parts.last() else null
    }
    
    private fun openToolWindow() {
        try {
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow("CodeMark")
            if (toolWindow != null) {
                toolWindow.show {
                    logger.info("[BOOKMARK_NAV] Tool window opened")
                }
            } else {
                logger.warn("[BOOKMARK_NAV] Tool window not found")
            }
        } catch (e: Exception) {
            logger.error("[BOOKMARK_NAV] Error opening tool window: ${e.message}", e)
        }
    }
    
    /**
     * 遍历书签树
     */
    private fun traverse(node: BookmarkNode, action: (BookmarkNode) -> Unit) {
        action(node)
        when (node) {
            is BookmarkNode.Group -> node.children.forEach { child -> traverse(child, action) }
            is BookmarkNode.Process -> node.steps.forEach { step -> traverse(step, action) }
            else -> {}
        }
    }
    
    fun dispose() {
        // 移除所有监听器
        attachedListeners.forEach { (editor, listener) ->
            try {
                editor.caretModel.removeCaretListener(listener)
            } catch (e: Exception) {
                logger.debug("[BOOKMARK_NAV] Error removing caret listener: ${e.message}")
            }
        }
        attachedListeners.clear()
        scope.cancel()
        lastHandledPosition.clear()
        pendingNavigation.clear()
    }
}
