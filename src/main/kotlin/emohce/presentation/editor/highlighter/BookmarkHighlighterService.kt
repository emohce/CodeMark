package emohce.presentation.editor.highlighter

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.icons.AllIcons
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import emohce.presentation.selection.SelectionBus
import emohce.presentation.toolwindow.BookmarkIntent
import emohce.presentation.toolwindow.BookmarkViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.ActionEvent
import javax.swing.JComponent
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class BookmarkHighlighterService(private val project: Project) {
    private val logger = Logger.getInstance(BookmarkHighlighterService::class.java)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val locator by lazy { ServiceLocator(project) }
    private val selectionBus by lazy { SelectionBus.getInstance(project) }
    private val toolWindowManager by lazy { ToolWindowManager.getInstance(project) }
    private val viewModel by lazy { locator.bookmarkViewModel }
    private val editorHighlighters = mutableMapOf<Editor, MutableList<com.intellij.openapi.editor.markup.RangeHighlighter>>()
    private val fileIndex = ConcurrentHashMap<String, List<MarkerEntry>>()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        logger.info("[GUTTER_HIGHLIGHT] service start")
        rebuildIndex()
        listenEditors()
        listenRepository()
        refreshOpenEditors()
    }

    fun dispose() {
        clearAll()
        scope.cancel()
    }

    private fun listenEditors() {
        val fem = FileEditorManager.getInstance(project)
        fem.addFileEditorManagerListener(object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                source.getSelectedTextEditor()?.let { refreshEditor(it, file) }
            }

            override fun selectionChanged(event: FileEditorManagerEvent) {
                val editor = event.manager.getSelectedTextEditor() ?: return
                val file = editor.virtualFile ?: return
                refreshEditor(editor, file)
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                source.getAllEditors(file).forEach { ed ->
                    if (ed is com.intellij.openapi.fileEditor.TextEditor) {
                        clearEditor(ed.editor)
                    }
                }
            }
        })
    }

    private fun listenRepository() {
        scope.launch {
            locator.bookmarkRepository.observeChanges().collectLatest { event ->
                logger.info("[GUTTER_HIGHLIGHT] repo event=${event.javaClass.simpleName}")
                rebuildIndex()
                refreshOpenEditors()
            }
        }
    }

    private fun refreshOpenEditors() {
        val fem = FileEditorManager.getInstance(project)
        val editors = fem.allEditors.filterIsInstance<com.intellij.openapi.fileEditor.TextEditor>()
        editors.forEach { ed ->
            ed.editor.virtualFile?.let { refreshEditor(ed.editor, it) }
        }
    }

    private fun refreshEditor(editor: Editor, file: VirtualFile) {
        scope.launch {
            val normalized = FileUtil.toSystemIndependentName(file.path)
            val entries = withContext(Dispatchers.IO) { fileIndex[normalized].orEmpty() }
            applyHighlighters(editor, entries)
        }
    }

    private fun rebuildIndex() {
        scope.launch(Dispatchers.IO) {
            val map = mutableMapOf<String, MutableList<MarkerEntry>>()
            val root = locator.bookmarkRepository.getRootNode()
            traverse(root) { node ->
                when (node) {
                    is BookmarkNode.Bookmark -> {
                        val path = FileUtil.toSystemIndependentName(node.filePath)
                        map.getOrPut(path) { mutableListOf() }
                            .add(MarkerEntry(node.uuid, node.name, node.line, node.filePath))
                    }
                    is BookmarkNode.Process -> {
                        val entryPath = node.entryFilePath
                        val entryLine = node.entryLine
                        if (entryPath != null && entryLine != null) {
                            val path = FileUtil.toSystemIndependentName(entryPath)
                            map.getOrPut(path) { mutableListOf() }
                                .add(MarkerEntry(node.uuid, node.name, entryLine, entryPath))
                        }
                    }
                    else -> Unit
                }
            }
            map.forEach { (k, v) -> v.sortBy { it.line } }
            fileIndex.clear()
            fileIndex.putAll(map)
            logger.info("[GUTTER_HIGHLIGHT] index rebuilt files=${map.size}")
        }
    }

    private fun applyHighlighters(editor: Editor, entries: List<MarkerEntry>) {
        clearEditor(editor)
        if (entries.isEmpty()) return
        val doc = editor.document
        val highlightList = mutableListOf<com.intellij.openapi.editor.markup.RangeHighlighter>()
        val colors = EditorColorsManager.getInstance().globalScheme
        val bg = colors.getAttributes(com.intellij.openapi.editor.colors.EditorColors.SEARCH_RESULT_ATTRIBUTES)?.backgroundColor
        entries.forEach { entry ->
            if (entry.line < 0 || entry.line >= doc.lineCount) return@forEach
            val start = doc.getLineStartOffset(entry.line)
            val end = doc.getLineEndOffset(entry.line)
            val attrs = bg?.let { com.intellij.openapi.editor.markup.TextAttributes(null, it, null, null, 0) }
            val hl = editor.markupModel.addRangeHighlighter(
                start,
                end,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                attrs,
                HighlighterTargetArea.LINES_IN_RANGE
            )
            hl.gutterIconRenderer = object : GutterIconRenderer() {
                override fun getIcon() = AllIcons.Nodes.Bookmark
                override fun getClickAction() = object : com.intellij.openapi.actionSystem.AnAction("Select Bookmark") {
                    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                        logger.info("[GUTTER_HIGHLIGHT] click node=${entry.nodeId} line=${entry.line}")
                        selectionBus.requestSelect(entry.nodeId)
                        toolWindowManager.getToolWindow("CodeRemarkTour")?.show(null)
                        flashLine(editor, entry.line)
                    }
                }
                override fun getPopupMenuActions(): com.intellij.openapi.actionSystem.ActionGroup? {
                    val group = com.intellij.openapi.actionSystem.DefaultActionGroup()
                    group.add(object : com.intellij.openapi.actionSystem.AnAction("Edit") {
                        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            scope.launch {
                                val node = locator.bookmarkRepository.findByUuid(entry.nodeId) ?: return@launch
                                viewModel.processIntent(BookmarkIntent.EditNode(node))
                            }
                        }
                    })
                    group.add(object : com.intellij.openapi.actionSystem.AnAction("Add Child Bookmark") {
                        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            scope.launch {
                                val parentId = entry.nodeId
                                val child = BookmarkNode.Bookmark(name = "New Bookmark", filePath = entry.filePath, line = entry.line)
                                viewModel.processIntent(BookmarkIntent.CreateBookmark(parentId, child, null))
                            }
                        }
                    })
                    group.add(object : com.intellij.openapi.actionSystem.AnAction("Delete") {
                        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            scope.launch { 
                                viewModel.processIntent(BookmarkIntent.DeleteNode(entry.nodeId))
                            }
                        }
                    })
                    return group
                }

                override fun isNavigateAction() = true
                override fun equals(other: Any?): Boolean = other is BookmarkGutterRenderer && other.id == entry.nodeId
                override fun hashCode(): Int = entry.nodeId.hashCode()
            }.let { BookmarkGutterRenderer(entry.nodeId, it) }
            highlightList.add(hl)
        }
        editorHighlighters[editor] = highlightList
    }

    private fun flashLine(editor: Editor, line: Int) {
        val doc = editor.document
        if (line < 0 || line >= doc.lineCount) return
        val start = doc.getLineStartOffset(line)
        val end = doc.getLineEndOffset(line)
        val attrs = com.intellij.openapi.editor.markup.TextAttributes(null, java.awt.Color(255, 235, 156), null, null, 0)
        val hl = editor.markupModel.addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1, attrs, HighlighterTargetArea.EXACT_RANGE)
        javax.swing.Timer(350) { _: ActionEvent? -> try { hl.dispose() } catch (_: Exception) {} }.apply { isRepeats = false; start() }
    }

    private fun clearEditor(editor: Editor) {
        editorHighlighters.remove(editor)?.forEach { hl ->
            try { hl.dispose() } catch (_: Exception) {}
        }
    }

    private fun clearAll() {
        editorHighlighters.keys.toList().forEach { clearEditor(it) }
        editorHighlighters.clear()
    }

    private fun traverse(node: BookmarkNode, visitor: (BookmarkNode) -> Unit) {
        visitor(node)
        when (node) {
            is BookmarkNode.Group -> node.children.forEach { traverse(it, visitor) }
            is BookmarkNode.Process -> node.steps.forEach { traverse(it, visitor) }
            else -> Unit
        }
    }

    data class MarkerEntry(
        val nodeId: String,
        val title: String,
        val line: Int,
        val filePath: String
    )

    private data class BookmarkGutterRenderer(val id: String, val delegate: GutterIconRenderer) : GutterIconRenderer() {
        override fun getIcon() = delegate.icon
        override fun getClickAction() = delegate.clickAction
        override fun getPopupMenuActions() = delegate.popupMenuActions
        override fun isNavigateAction() = delegate.isNavigateAction
        override fun equals(other: Any?) = other is BookmarkGutterRenderer && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    companion object {
        fun getInstance(project: Project): BookmarkHighlighterService = project.service()
    }
}
