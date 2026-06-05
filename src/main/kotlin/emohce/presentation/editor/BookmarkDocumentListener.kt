package emohce.presentation.editor

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import emohce.presentation.toolwindow.BookmarkIntent
import emohce.presentation.toolwindow.BookmarkViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/**
 * 监听所有打开的文件中有书签的文件，自动更新书签行�? */
class BookmarkDocumentListener(private val project: Project) {
    private val logger = Logger.getInstance(BookmarkDocumentListener::class.java)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val documentListeners = ConcurrentHashMap<Document, DocumentChangeListener>()
    private val fileBookmarks = ConcurrentHashMap<String, MutableList<BookmarkNode.Bookmark>>()
    private val documentMarkers = ConcurrentHashMap<Document, FileMarkerState>()
    private var viewModel: BookmarkViewModel? = null

    init {
        setupFileEditorListener()
        loadBookmarksForAllOpenFiles()
    }

    private fun getDocumentSafely(file: VirtualFile): Document? {
        return try {
            ReadAction.compute<Document?, Throwable> {
                FileDocumentManager.getInstance().getDocument(file)
            }
        } catch (e: Throwable) {
            logger.debug("Error getting document for file ${file.path}: ${e.message}")
            null
        }
    }

    private fun collectBookmarksForFile(root: BookmarkNode, targetPath: String): List<BookmarkNode.Bookmark> {
        val normalizedTarget = FileUtil.toSystemIndependentName(targetPath)
        val list = mutableListOf<BookmarkNode.Bookmark>()
        fun traverse(node: BookmarkNode) {
            when (node) {
                is BookmarkNode.Bookmark -> {
                    val path = FileUtil.toSystemIndependentName(node.filePath)
                    if (path == normalizedTarget) list.add(node)
                }
                is BookmarkNode.Group -> node.children.forEach { traverse(it) }
                is BookmarkNode.Process -> node.steps.forEach { traverse(it) }
                else -> Unit
            }
        }
        traverse(root)
        return list
    }

    private fun ensureMarkers(document: Document, filePath: String, bookmarks: List<BookmarkNode.Bookmark>) {
        val state = documentMarkers.getOrPut(document) { FileMarkerState(filePath) }
        val normalizedPath = FileUtil.toSystemIndependentName(filePath)

        // 删除不存在的书签 marker
        val incomingIds = bookmarks.map { it.uuid }.toSet()
        val toRemove = state.markers.keys - incomingIds
        toRemove.forEach { id ->
            state.markers.remove(id)?.marker?.dispose()
        }

        bookmarks.forEach { bookmark ->
            val existing = state.markers[bookmark.uuid]
            if (existing == null || !existing.marker.isValid) {
                val line = bookmark.line
                if (line >= 0 && line < document.lineCount) {
                    val start = document.getLineStartOffset(line)
                    val marker = document.createRangeMarker(start, start)
                    // Keep the marker anchored to original content when lines are inserted/deleted at the boundary.
                    marker.isGreedyToLeft = false
                    marker.isGreedyToRight = false
                    state.markers[bookmark.uuid] = MarkerInfo(bookmark, marker, line)
                } else {
                    logger.debug("Skip marker creation out of bounds path=$normalizedPath line=$line")
                }
            } else {
                // Sync marker position when the stored line changes (e.g. external updates).
                existing.bookmark = bookmark
                val currentLine = document.getLineNumber(existing.marker.startOffset)
                if (currentLine != bookmark.line && bookmark.line >= 0 && bookmark.line < document.lineCount) {
                    existing.marker.dispose()
                    val start = document.getLineStartOffset(bookmark.line)
                    val marker = document.createRangeMarker(start, start)
                    marker.isGreedyToLeft = false
                    marker.isGreedyToRight = false
                    state.markers[bookmark.uuid] = MarkerInfo(bookmark, marker, bookmark.line)
                } else {
                    existing.lastSyncedLine = existing.lastSyncedLine.coerceAtLeast(0)
                }
            }
        }
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
            val hasBookmarks = withContext<Boolean>(Dispatchers.IO) {
                val locator = ServiceLocator.get(project)
                val root = locator.bookmarkRepository.getRootNode()
                val bookmarks = collectBookmarksForFile(root, filePath)
                fileBookmarks[filePath] = bookmarks.toMutableList()
                bookmarks.isNotEmpty()
            }

            if (hasBookmarks && !documentListeners.containsKey(document)) {
                withContext(Dispatchers.Main) {
                    ensureMarkers(document, filePath, fileBookmarks[filePath].orEmpty())
                    val listener = DocumentChangeListener(filePath, document)
                    documentListeners[document] = listener
                    document.addDocumentListener(listener, project)
                }
            }
        }
    }

    private fun detachListenerFromFile(file: VirtualFile) {
        val document = getDocumentSafely(file) ?: return
        val listener = documentListeners.remove(document)
        listener?.let {
            document.removeDocumentListener(it)
        }
        val filePath = FileUtil.toSystemIndependentName(file.path)
        fileBookmarks.remove(filePath)
        documentMarkers.remove(document)?.dispose()
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
                withContext(Dispatchers.Main) {
                    val document = getDocumentSafely(file)
                    if (document != null) {
                        ensureMarkers(document, normalizedPath, bookmarks)
                    }
                }
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

    suspend fun flushForFile(filePath: String) {
        val normalized = FileUtil.toSystemIndependentName(filePath)
        val entry = documentMarkers.entries.firstOrNull { FileUtil.toSystemIndependentName(it.value.filePath) == normalized }
        val document = entry?.key ?: return
        val state = entry.value
        flushMarkers(document, state)
    }

    private inner class DocumentChangeListener(
        private val filePath: String,
        private val document: Document
    ) : DocumentListener {
        private var isProcessing = false
        private var flushJob: kotlinx.coroutines.Job? = null

        override fun documentChanged(event: DocumentEvent) {
            if (isProcessing) return
            isProcessing = true

            try {
                val normalizedPath = FileUtil.toSystemIndependentName(filePath)
                val state = documentMarkers[document] ?: return

                // 检查失效 marker（被删除的行）
                val invalid = state.markers.values.filter { !it.marker.isValid }
                if (invalid.isNotEmpty()) {
                    val bookmarksToDelete = invalid.mapNotNull { it.bookmark }
                    invalid.forEach { info -> state.markers.remove(info.bookmark?.uuid)?.marker?.dispose() }
                    if (bookmarksToDelete.isNotEmpty()) {
                        SwingUtilities.invokeLater {
                            handleBookmarksDeletion(bookmarksToDelete, normalizedPath)
                        }
                    }
                }

                // 防抖写回行号（缩短延迟以减少错位时间窗口）
                flushJob?.cancel()
                flushJob = scope.launch {
                    delay(30)
                    flushMarkers(document, state)
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

    }

    private suspend fun flushMarkers(document: Document, state: FileMarkerState) {
        val updates = withContext(Dispatchers.Main) {
            ReadAction.compute<List<MarkerLineUpdate>, Throwable> {
                state.markers.values.mapNotNull { info ->
                    val marker = info.marker
                    val bookmark = info.bookmark ?: return@mapNotNull null
                    if (!marker.isValid) return@mapNotNull null
                    val line = document.getLineNumber(marker.startOffset)
                    if (line != info.lastSyncedLine) MarkerLineUpdate(info, bookmark.uuid, line) else null
                }
            }
        }
        updates.forEach { update ->
            viewModel?.processIntent(BookmarkIntent.UpdateBookmarkLineFromDocument(update.nodeId, update.line))
            update.info.lastSyncedLine = update.line
        }
        // Do not refresh gutter here: UpdateBookmarkLineFromDocument is asynchronous.
        // Refreshing immediately can read stale repository lines and make gutter/line-end hints drift.
        // BookmarkViewModel/BookmarkHighlighterService refresh after NodeLineSynced when the repository is updated.
    }

    private data class MarkerLineUpdate(
        val info: MarkerInfo,
        val nodeId: String,
        val line: Int
    )

    private data class MarkerInfo(
        var bookmark: BookmarkNode.Bookmark?,
        val marker: RangeMarker,
        var lastSyncedLine: Int
    )

    private class FileMarkerState(val filePath: String) {
        val markers: MutableMap<String, MarkerInfo> = ConcurrentHashMap()
        fun dispose() {
            markers.values.forEach { runCatching { it.marker.dispose() } }
            markers.clear()
        }
    }
}
