package emohce.presentation.editor

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.EditorNotifications
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import emohce.presentation.toolwindow.BookmarkIntent
import emohce.presentation.toolwindow.BookmarkViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/**
 * 监听所有打开的文件中有书签的文件，自动更新书签行号
 */
class BookmarkDocumentListener(private val project: Project) {
    private val logger = Logger.getInstance(BookmarkDocumentListener::class.java)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val documentListeners = ConcurrentHashMap<Document, DocumentChangeListener>()
    private val fileBookmarks = ConcurrentHashMap<String, MutableList<BookmarkNode.Bookmark>>()
    private var viewModel: BookmarkViewModel? = null

    init {
        setupFileEditorListener()
        loadBookmarksForAllOpenFiles()
    }

    fun setViewModel(viewModel: BookmarkViewModel) {
        this.viewModel = viewModel
    }

    private fun setupFileEditorListener() {
        val messageBus = project.messageBus.connect(scope)
        messageBus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                attachListenerToFile(file)
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                detachListenerFromFile(file)
            }
        })
    }

    private fun loadBookmarksForAllOpenFiles() {
        scope.launch {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val openFiles = fileEditorManager.openFiles
            openFiles.forEach { file ->
                attachListenerToFile(file)
            }
        }
    }

    private fun attachListenerToFile(file: VirtualFile) {
        // 使用 ReadAction 访问 Document
        val document = try {
            com.intellij.openapi.application.ReadAction.compute<com.intellij.openapi.editor.Document?, Throwable> {
                FileDocumentManager.getInstance().getDocument(file)
            }
        } catch (e: Throwable) {
            logger.debug("Error getting document for file ${file.path}: ${e.message}")
            return
        } ?: return
        val filePath = FileUtil.toSystemIndependentName(file.path)
        
        // 检查文件是否有书签
        scope.launch {
            val hasBookmarks = withContext(Dispatchers.IO) {
                val locator = ServiceLocator.get(project)
                val root = locator.bookmarkRepository.getRootNode()
                val bookmarks = collectBookmarksForFile(root, filePath)
                fileBookmarks[filePath] = bookmarks.toMutableList()
                bookmarks.isNotEmpty()
            }
            
            if (hasBookmarks && !documentListeners.containsKey(document)) {
                val listener = DocumentChangeListener(filePath, document)
                documentListeners[document] = listener
                document.addDocumentListener(listener, project)
            }
        }
    }

    private fun detachListenerFromFile(file: VirtualFile) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val listener = documentListeners.remove(document)
        listener?.let {
            document.removeDocumentListener(it)
        }
        val filePath = FileUtil.toSystemIndependentName(file.path)
        fileBookmarks.remove(filePath)
    }

    private fun collectBookmarksForFile(root: BookmarkNode, filePath: String): List<BookmarkNode.Bookmark> {
        val bookmarks = mutableListOf<BookmarkNode.Bookmark>()
        traverse(root) { node ->
            if (node is BookmarkNode.Bookmark && FileUtil.toSystemIndependentName(node.filePath) == filePath) {
                bookmarks.add(node)
            }
        }
        return bookmarks
    }

    private fun traverse(node: BookmarkNode, visitor: (BookmarkNode) -> Unit) {
        visitor(node)
        when (node) {
            is BookmarkNode.Group -> node.children.forEach { traverse(it, visitor) }
            is BookmarkNode.Process -> node.steps.forEach { traverse(it, visitor) }
            else -> Unit
        }
    }

    fun onBookmarksChanged(filePath: String) {
        val normalizedPath = FileUtil.toSystemIndependentName(filePath)
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(normalizedPath) ?: return
        
        // 重新加载该文件的书签
        scope.launch {
            val locator = ServiceLocator.get(project)
            val root = withContext(Dispatchers.IO) {
                locator.bookmarkRepository.getRootNode()
            }
            val bookmarks = collectBookmarksForFile(root, normalizedPath)
            fileBookmarks[normalizedPath] = bookmarks.toMutableList()
            
            // 如果文件已打开，确保有监听器
            if (FileEditorManager.getInstance(project).isFileOpen(file)) {
                attachListenerToFile(file)
            }
        }
    }

    fun dispose() {
        documentListeners.forEach { (document, listener) ->
            document.removeDocumentListener(listener)
        }
        documentListeners.clear()
        fileBookmarks.clear()
        scope.cancel()
    }

    private inner class DocumentChangeListener(
        private val filePath: String,
        private val document: Document
    ) : DocumentListener {
        private var isProcessing = false

        override fun documentChanged(event: DocumentEvent) {
            if (isProcessing) return
            isProcessing = true

            try {
                val normalizedPath = FileUtil.toSystemIndependentName(filePath)
                val bookmarks = fileBookmarks[normalizedPath] ?: return

                val changeOffset = event.offset
                val newLength = event.newLength
                val oldLength = event.oldLength

                val changeLine = document.getLineNumber(changeOffset)
                val newText = if (newLength > 0) {
                    document.charsSequence.subSequence(changeOffset, changeOffset + newLength).toString()
                } else ""
                val newNewlines = newText.count { it == '\n' }
                val newLines = if (newLength > 0) newNewlines + 1 else 0

                // oldLines 无旧片段时按 oldLength 估算；删除多行时可能不精确
                val avgLineLength = if (document.lineCount > 0) {
                    document.textLength / document.lineCount
                } else 80
                val oldLines = if (oldLength > 0) {
                    maxOf(1, (oldLength + avgLineLength - 1) / maxOf(1, avgLineLength))
                } else 0

                val lineDelta = newLines - oldLines
                if (lineDelta == 0) return

                val lineStartOffset = document.getLineStartOffset(changeLine)
                val bookmarksToUpdate = mutableListOf<Pair<BookmarkNode.Bookmark, Int>>()
                val bookmarksToDelete = mutableListOf<BookmarkNode.Bookmark>()

                bookmarks.forEach { bookmark ->
                    val L = bookmark.line
                    if (L < changeLine) return@forEach

                    when {
                        lineDelta > 0 -> {
                            if (L == changeLine && changeOffset > lineStartOffset) return@forEach
                            bookmarksToUpdate.add(bookmark to (L + lineDelta))
                        }
                        lineDelta < 0 -> {
                            val deletedLines = -lineDelta
                            if (L <= changeLine + oldLines - 1) {
                                if (L == changeLine) {
                                    bookmarksToDelete.add(bookmark)
                                } else {
                                    val newLine = L - deletedLines
                                    if (newLine >= changeLine) bookmarksToUpdate.add(bookmark to newLine)
                                    else bookmarksToDelete.add(bookmark)
                                }
                            } else {
                                bookmarksToUpdate.add(bookmark to (L + lineDelta))
                            }
                        }
                    }
                }

                if (bookmarksToDelete.isNotEmpty()) {
                    SwingUtilities.invokeLater {
                        handleBookmarksDeletion(bookmarksToDelete, normalizedPath)
                    }
                }
                if (bookmarksToUpdate.isNotEmpty()) {
                    scope.launch {
                        updateBookmarkLines(bookmarksToUpdate)
                    }
                }
            } finally {
                isProcessing = false
            }
        }

        private fun handleBookmarksDeletion(bookmarks: List<BookmarkNode.Bookmark>, filePath: String) {
            val bookmarkNames = bookmarks.joinToString(", ") { it.name.ifBlank { "Unnamed" } }
            val message = "The following bookmarks are on deleted lines:\n$bookmarkNames\n\nDelete these bookmarks?"
            val result = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project,
                message,
                "Delete Bookmarks",
                com.intellij.openapi.ui.Messages.getQuestionIcon()
            )

            if (result == com.intellij.openapi.ui.Messages.YES) {
                scope.launch {
                    bookmarks.forEach { bookmark ->
                        viewModel?.processIntent(BookmarkIntent.DeleteNode(bookmark.uuid))
                    }
                }
            }
        }

        private suspend fun updateBookmarkLines(updates: List<Pair<BookmarkNode.Bookmark, Int>>) {
            withContext(Dispatchers.IO) {
                updates.forEach { (bookmark, newLine) ->
                    viewModel?.processIntent(BookmarkIntent.UpdateBookmarkLineFromDocument(bookmark.uuid, newLine))
                }
            }
        }
    }
}
