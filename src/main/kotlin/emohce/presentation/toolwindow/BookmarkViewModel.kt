package emohce.presentation.toolwindow

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import emohce.core.coroutine.CoroutineDispatchers
import emohce.domain.event.BookmarkEvent
import emohce.domain.model.BookmarkNode
import emohce.domain.model.ProcessProgress
import emohce.domain.repository.BookmarkRepository
import emohce.domain.repository.ReferenceRepository
import emohce.domain.usecase.navigation.ProcessNavigationUseCase
import emohce.domain.usecase.reference.DetectCircularRefUseCase
import emohce.domain.usecase.reference.SyncReferencesUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.diagnostic.Logger
import emohce.presentation.editor.gutter.BookmarkLineMarkerProvider
import emohce.presentation.index.BookmarkIndexService
import java.nio.file.Paths

class BookmarkViewModel(
    private val project: Project,
    private val bookmarkRepository: BookmarkRepository,
    private val referenceRepository: ReferenceRepository,
    private val processNavigationUseCase: ProcessNavigationUseCase,
    private val syncReferencesUseCase: SyncReferencesUseCase,
    private val detectCircularRefUseCase: DetectCircularRefUseCase,
    private val dispatchers: CoroutineDispatchers
) {
    private val logger = Logger.getInstance(BookmarkViewModel::class.java)
    private val scope = CoroutineScope(dispatchers.main + SupervisorJob())

    private val _state = MutableStateFlow(BookmarkViewState())
    val state: StateFlow<BookmarkViewState> = _state.asStateFlow()

    private val _sideEffects = MutableSharedFlow<BookmarkSideEffect>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sideEffects: SharedFlow<BookmarkSideEffect> = _sideEffects.asSharedFlow()

    private var documentListener: emohce.presentation.editor.BookmarkDocumentListener? = null

    private val indexService = BookmarkIndexService.getInstance(project)

    init {
        loadBookmarks()
        observeChanges()
        observeFileChanges()
    }

    fun setDocumentListener(listener: emohce.presentation.editor.BookmarkDocumentListener) {
        this.documentListener = listener
    }

    fun processIntent(intent: BookmarkIntent) {
        logger.info("[PROCESS_INTENT] Received intent: ${intent.javaClass.simpleName}")
        scope.launch {
            try {
                when (intent) {
                    is BookmarkIntent.SelectNode -> {
                        logger.info("[PROCESS_INTENT] Handling SelectNode: nodeId=${intent.nodeId}")
                        handleSelectNode(intent.nodeId)
                    }
                    is BookmarkIntent.CreateBookmark -> {
                        logger.info("[PROCESS_INTENT] Handling CreateBookmark: nodeId=${intent.bookmark.uuid}, filePath=${intent.bookmark.filePath}, line=${intent.bookmark.line}")
                        handleCreateBookmark(intent.parentId, intent.bookmark, intent.insertIndex)
                    }
                is BookmarkIntent.CreateGroup -> handleCreateGroup(intent.parentId, intent.group, intent.insertIndex)
                is BookmarkIntent.CreateProcess -> handleCreateProcess(intent.parentId, intent.process, intent.insertIndex)
                is BookmarkIntent.CreateDescriptive -> handleCreateDescriptive(intent.parentId, intent.note, intent.insertIndex)
                is BookmarkIntent.EditNode -> handleEditNode(intent.node)
                is BookmarkIntent.MoveNode -> handleMoveNode(intent.nodeId, intent.newParentId, intent.newIndex)
                is BookmarkIntent.DeleteNode -> handleDeleteNode(intent.nodeId)
                is BookmarkIntent.CreateReference -> handleCreateReference(intent.sourceId, intent.targetId)
                is BookmarkIntent.SyncReferences -> handleSyncReferences(intent.sourceId)
                is BookmarkIntent.DeleteReferences -> handleDeleteReferences(intent.sourceId)
                is BookmarkIntent.NavigateToBookmark -> handleNavigateToBookmark(intent.bookmark)
                is BookmarkIntent.NavigateToNextInProcess -> handleNavigateNext()
                is BookmarkIntent.NavigateToPrevInProcess -> handleNavigatePrevious()
                is BookmarkIntent.Search -> handleSearch(intent.query, intent.filters)
                is BookmarkIntent.ClearSearch -> handleClearSearch()
                is BookmarkIntent.ExpandNode -> handleExpandNode(intent.nodeId)
                is BookmarkIntent.CollapseNode -> handleCollapseNode(intent.nodeId)
                is BookmarkIntent.Refresh -> {
                        logger.info("[PROCESS_INTENT] Handling Refresh")
                        loadBookmarks()
                    }
                }
            } catch (e: Exception) {
                logger.error("[PROCESS_INTENT] Error handling intent ${intent.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    private fun loadBookmarks() {
        scope.launch { reloadBookmarks() }
    }

    private suspend fun reloadBookmarks() {
        logger.info("[RELOAD_BOOKMARKS] Step 1: Starting reload...")
        _state.update { it.copy(isLoading = true, error = null) }
        try {
            // 如果 repository 支持重新加载，先重新加载数据
            if (bookmarkRepository is emohce.data.repository.BookmarkRepositoryImpl) {
                logger.info("[RELOAD_BOOKMARKS] Step 2: Reloading store...")
                withContext(dispatchers.io) {
                    (bookmarkRepository as emohce.data.repository.BookmarkRepositoryImpl).getStore().reload()
                }
                logger.info("[RELOAD_BOOKMARKS] Step 3: Store reloaded")
            }
            
            logger.info("[RELOAD_BOOKMARKS] Step 4: Getting root node...")
            val root = withContext(dispatchers.io) {
                bookmarkRepository.getRootNode()
            }
            logger.info("[RELOAD_BOOKMARKS] Step 5: Root node retrieved, uuid=${root.uuid}")
            
            logger.info("[RELOAD_BOOKMARKS] Step 6: Getting references...")
            val references = withContext(dispatchers.io) {
                referenceRepository.getAllReferences()
            }
            logger.info("[RELOAD_BOOKMARKS] Step 7: References retrieved, count=${references.size}")
            
            val counts = references.groupingBy { it.sourceId }.eachCount()
            val targets = references.map { it.targetId }.toSet()
            val targetsBySource = references.groupBy({ it.sourceId }, { it.targetId })
            val sourcesByTarget = references.groupBy({ it.targetId }, { it.sourceId })
            
            logger.info("[RELOAD_BOOKMARKS] Step 8: Updating state...")
            _state.update {
                it.copy(
                    rootNode = root,
                    isLoading = false,
                    referenceCounts = counts,
                    referenceTargets = targets,
                    referenceTargetsBySource = targetsBySource,
                    referenceSourcesByTarget = sourcesByTarget
                )
            }
            // Rebuild index for fast lookup
            indexService.rebuild(root)
            logger.info("[RELOAD_BOOKMARKS] Step 9: State updated, reload complete")
        } catch (e: Exception) {
            logger.error("[RELOAD_BOOKMARKS] Error: ${e.message}", e)
            _state.update { it.copy(error = e.message, isLoading = false) }
        }
    }

    private fun observeChanges() {
        scope.launch {
            bookmarkRepository.observeChanges().collect { event ->
                when (event) {
                    is BookmarkEvent.NodeAdded -> {
                        reloadBookmarks()
                        _sideEffects.emit(BookmarkSideEffect.SelectNode(event.node.uuid))
                        val path = when (val node = event.node) {
                            is BookmarkNode.Bookmark -> node.filePath
                            is BookmarkNode.Process -> node.entryFilePath
                            else -> null
                        }
                        path?.let { refreshInlaysAndGutter(it) }
                    }
                    is BookmarkEvent.NodeUpdated -> {
                        reloadBookmarks()
                        // 等待 reloadBookmarks 完成后再选择节点，确保使用最新数据
                        _sideEffects.emit(BookmarkSideEffect.SelectNode(event.node.uuid))
                        // 刷新编辑器 Inlay
                        when (val node = event.node) {
                            is BookmarkNode.Bookmark -> {
                                refreshInlaysAndGutter(node.filePath)
                            }
                            is BookmarkNode.Process -> {
                                node.entryFilePath?.let { refreshInlaysAndGutter(it) }
                            }
                            else -> Unit
                        }
                    }
                    is BookmarkEvent.NodeRemoved -> {
                        reloadBookmarks()
                        // 无法直接取路径，改为全量清理缓存
                        BookmarkLineMarkerProvider.clearAllCache()
                    }
                    is BookmarkEvent.NodeMoved -> {
                        reloadBookmarks()
                        BookmarkLineMarkerProvider.clearAllCache()
                    }
                    is BookmarkEvent.ReferenceSynced -> {
                        // 引用刷新可能影响提示，清空缓存等待下一轮收集
                        BookmarkLineMarkerProvider.clearAllCache()
                    }
                }
            }
        }
    }

    private fun observeFileChanges() {
        val basePath = project.basePath ?: return
        val bookmarkxPath = Paths.get(basePath, ".bookmarkx", "bookmarkx.json")
        val normalizedPath = FileUtil.toSystemIndependentName(bookmarkxPath.toString())
        
        val messageBus = project.messageBus.connect(scope)
        messageBus.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                var shouldReload = false
                
                for (event in events) {
                    val file = event.file ?: continue
                    val filePath = FileUtil.toSystemIndependentName(file.path)
                    
                    // 检查是否是 bookmarkx.json 文件变化
                    if (filePath == normalizedPath) {
                        shouldReload = true
                        break
                    }
                }
                
                if (shouldReload) {
                    // 文件变化后立即刷新，无延迟
                    scope.launch {
                        // 重新加载数据
                        reloadBookmarks()
                        
                        // 刷新所有相关的编辑器 Inlay
                        val root = withContext(dispatchers.io) {
                            bookmarkRepository.getRootNode()
                        }
                        val affectedPaths = mutableSetOf<String>()
                        traverseBookmarks(root) { node ->
                            when (node) {
                                is BookmarkNode.Bookmark -> {
                                    affectedPaths.add(node.filePath)
                                }
                                is BookmarkNode.Process -> {
                                    node.entryFilePath?.let { affectedPaths.add(it) }
                                }
                                else -> Unit
                            }
                        }
                        indexService.rebuild(root)
                        affectedPaths.forEach { path ->
                            refreshInlaysAndGutter(path)
                        }
                    }
                }
            }
        })
    }

    private suspend fun refreshInlaysAndGutter(path: String) {
        BookmarkLineMarkerProvider.clearCache(path)
        _sideEffects.emit(BookmarkSideEffect.RefreshInlays(path))
    }
    
    private fun traverseBookmarks(node: BookmarkNode, visitor: (BookmarkNode) -> Unit) {
        visitor(node)
        when (node) {
            is BookmarkNode.Group -> node.children.forEach { traverseBookmarks(it, visitor) }
            is BookmarkNode.Process -> node.steps.forEach { traverseBookmarks(it, visitor) }
            else -> Unit
        }
    }

    private suspend fun handleSelectNode(nodeId: String) {
        _state.update { it.copy(selectedNodeId = nodeId) }
    }

    private suspend fun handleCreateBookmark(parentId: String?, bookmark: BookmarkNode.Bookmark, insertIndex: Int?) {
        logger.info("=== [CREATE_BOOKMARK_START] ===")
        logger.info("[CREATE_BOOKMARK] Step 1: Creating bookmark - nodeId=${bookmark.uuid}, filePath=${bookmark.filePath}, line=${bookmark.line}, parentId=$parentId, insertIndex=$insertIndex")
        
        bookmarkRepository.create(bookmark, parentId, insertIndex)
        logger.info("[CREATE_BOOKMARK] Step 2: Bookmark created in repository - nodeId=${bookmark.uuid}")
        
        logger.info("[CREATE_BOOKMARK] Step 3: Reloading bookmarks...")
        reloadBookmarks()
        logger.info("[CREATE_BOOKMARK] Step 4: Bookmarks reloaded")
        
        logger.info("[CREATE_BOOKMARK] Step 5: Emitting SelectNode side effect - nodeId=${bookmark.uuid}")
        _sideEffects.emit(BookmarkSideEffect.SelectNode(bookmark.uuid))
        
        logger.info("[CREATE_BOOKMARK] Step 6: Emitting RefreshInlays side effect - filePath=${bookmark.filePath}")
        _sideEffects.emit(BookmarkSideEffect.RefreshInlays(bookmark.filePath))
        
        logger.info("[CREATE_BOOKMARK] Step 7: Notifying document listener - filePath=${bookmark.filePath}")
        documentListener?.onBookmarksChanged(bookmark.filePath)
        
        logger.info("=== [CREATE_BOOKMARK_END] ===")
    }

    private suspend fun handleCreateGroup(parentId: String?, group: BookmarkNode.Group, insertIndex: Int?) {
        bookmarkRepository.create(group, parentId, insertIndex)
        reloadBookmarks()
        _sideEffects.emit(BookmarkSideEffect.SelectNode(group.uuid))
    }

    private suspend fun handleCreateProcess(parentId: String?, process: BookmarkNode.Process, insertIndex: Int?) {
        bookmarkRepository.create(process, parentId, insertIndex)
        reloadBookmarks()
        _sideEffects.emit(BookmarkSideEffect.SelectNode(process.uuid))
        process.entryFilePath?.let { _sideEffects.emit(BookmarkSideEffect.RefreshInlays(it)) }
    }

    private suspend fun handleCreateDescriptive(parentId: String?, note: BookmarkNode.DescriptiveBookmark, insertIndex: Int?) {
        bookmarkRepository.create(note, parentId, insertIndex)
        reloadBookmarks()
        _sideEffects.emit(BookmarkSideEffect.SelectNode(note.uuid))
    }

    private suspend fun handleEditNode(node: BookmarkNode) {
        bookmarkRepository.update(node)
        if (node is BookmarkNode.Bookmark) {
            val count = referenceRepository.getReferenceCount(node.uuid)
            if (count > 0) {
                syncReferencesWithRetry(node.uuid, notifyOnSuccess = false)
            }
            // 刷新编辑器 Inlay 提示
            _sideEffects.emit(BookmarkSideEffect.RefreshInlays(node.filePath))
            // 通知文档监听器书签已变化
            documentListener?.onBookmarksChanged(node.filePath)
        } else if (node is BookmarkNode.Process) {
            // 刷新流程入口的 Inlay 提示
            node.entryFilePath?.let { 
                _sideEffects.emit(BookmarkSideEffect.RefreshInlays(it))
                documentListener?.onBookmarksChanged(it)
            }
        }
        // 编辑后立即刷新树形结构和 JSON
        reloadBookmarks()
        
        // 编辑后刷新打开的 bookmarkx.json 编辑器
        _sideEffects.emit(BookmarkSideEffect.RefreshBookmarkxJson)
        
        // 编辑后导航到书签位置（确保编辑器显示最新位置）
        if (node is BookmarkNode.Bookmark) {
            _sideEffects.emit(BookmarkSideEffect.NavigateToFile(node.filePath, node.line, node.column))
        } else if (node is BookmarkNode.Process) {
            node.entryFilePath?.let { path ->
                val line = node.entryLine ?: 0
                _sideEffects.emit(BookmarkSideEffect.NavigateToFile(path, line, 0))
            }
        }
    }

    private suspend fun handleMoveNode(nodeId: String, newParentId: String?, newIndex: Int) {
        bookmarkRepository.move(nodeId, newParentId, newIndex)
    }

    private suspend fun handleDeleteNode(nodeId: String) {
        val node = bookmarkRepository.findByUuid(nodeId)
        val filePath = when (node) {
            is BookmarkNode.Bookmark -> {
                referenceRepository.deleteAllReferences(node.uuid)
                referenceRepository.deleteAllReferencesForTarget(node.uuid)
                node.filePath
            }
            is BookmarkNode.Process -> node.entryFilePath
            else -> null
        }
        
        bookmarkRepository.delete(nodeId)
        // observeChanges() 监听器会自动调用 reloadBookmarks() 和 clearAllCache()
        
        // 手动刷新特定文件的行末 hints（observeChanges 无法获取文件路径）
        filePath?.let { refreshInlaysAndGutter(it) }
        
        // 通知文档监听器（触发 BookmarkHighlighterService 刷新）
        filePath?.let { documentListener?.onBookmarksChanged(it) }
    }

    private suspend fun handleCreateReference(sourceId: String, targetId: String) {
        val hasCycle = detectCircularRefUseCase.execute(sourceId, targetId)
        if (hasCycle) {
            _sideEffects.emit(
                BookmarkSideEffect.ShowNotification(
                    "Circular reference detected",
                    NotificationType.WARNING
                )
            )
            return
        }
        referenceRepository.createReference(sourceId, targetId)
        val sourceNode = bookmarkRepository.findByUuid(sourceId) as? BookmarkNode.Bookmark
        val targetNode = bookmarkRepository.findByUuid(targetId) as? BookmarkNode.Bookmark
        _sideEffects.emit(
            BookmarkSideEffect.ShowNotification(
                "Reference created",
                NotificationType.INFORMATION
            )
        )
        sourceNode?.let { _sideEffects.emit(BookmarkSideEffect.SelectNode(it.uuid)) }
        targetNode?.let { _sideEffects.emit(BookmarkSideEffect.SelectNode(it.uuid)) }
    }

    private suspend fun handleSyncReferences(sourceId: String) {
        when (val result = syncReferencesWithRetry(sourceId, notifyOnSuccess = true)) {
            is SyncReferencesUseCase.SyncResult.SourceNotFound -> {
                _sideEffects.emit(
                    BookmarkSideEffect.ShowNotification(
                        "Source not found",
                        NotificationType.WARNING
                    )
                )
            }
            is SyncReferencesUseCase.SyncResult.NoReferences -> {
                _sideEffects.emit(
                    BookmarkSideEffect.ShowNotification(
                        "No references to sync",
                        NotificationType.INFORMATION
                    )
                )
            }
            is SyncReferencesUseCase.SyncResult.Success -> {
                val errors = result.errors.size
                val message = if (errors > 0) {
                    "Synced ${result.count} references, $errors failed"
                } else {
                    "Synced ${result.count} references"
                }
                _sideEffects.emit(
                    BookmarkSideEffect.ShowNotification(
                        message,
                        if (errors > 0) NotificationType.WARNING else NotificationType.INFORMATION
                    )
                )
            }
        }
    }

    private suspend fun syncReferencesWithRetry(
        sourceId: String,
        notifyOnSuccess: Boolean
    ): SyncReferencesUseCase.SyncResult {
        val first = syncReferencesUseCase.execute(sourceId)
        if (first !is SyncReferencesUseCase.SyncResult.Success) {
            return first
        }
        if (first.errors.isEmpty()) {
            if (notifyOnSuccess) {
                return first
            }
            return first
        }
        val retry = syncReferencesUseCase.execute(sourceId)
        if (retry is SyncReferencesUseCase.SyncResult.Success && retry.errors.isNotEmpty()) {
            _sideEffects.emit(
                BookmarkSideEffect.ShowNotification(
                    "Reference sync has ${retry.errors.size} failures after retry",
                    NotificationType.WARNING
                )
            )
        }
        return retry
    }

    private suspend fun handleDeleteReferences(sourceId: String) {
        referenceRepository.deleteAllReferences(sourceId)
        _sideEffects.emit(
            BookmarkSideEffect.ShowNotification(
                "References deleted",
                NotificationType.INFORMATION
            )
        )
    }

    private suspend fun handleNavigateToBookmark(bookmark: BookmarkNode.Bookmark) {
        logger.info("handleNavigateToBookmark: bookmark=${bookmark.uuid}, filePath=${bookmark.filePath}, line=${bookmark.line}, column=${bookmark.column}")
        _state.update { it.copy(selectedNodeId = bookmark.uuid) }
        logger.info("Emitting NavigateToFile side effect")
        _sideEffects.emit(BookmarkSideEffect.NavigateToFile(bookmark.filePath, bookmark.line, bookmark.column))
        logger.info("Emitting SelectNode side effect")
        _sideEffects.emit(BookmarkSideEffect.SelectNode(bookmark.uuid))
        logger.info("Emitting ScrollToSelected side effect")
        _sideEffects.emit(BookmarkSideEffect.ScrollToSelected)

        val progress = processNavigationUseCase.getProgress(bookmark)
        _state.update { it.copy(processProgress = progress) }
        logger.info("handleNavigateToBookmark completed")
    }

    private suspend fun handleNavigateNext() {
        val currentId = _state.value.selectedNodeId ?: return
        val current = bookmarkRepository.findByUuid(currentId) as? BookmarkNode.Bookmark ?: return

        val next = processNavigationUseCase.findNext(current)
        if (next != null) {
            handleNavigateToBookmark(next)
        } else {
            _sideEffects.emit(BookmarkSideEffect.ShowNotification("Reached end of process", NotificationType.INFORMATION))
        }
    }

    private suspend fun handleNavigatePrevious() {
        val currentId = _state.value.selectedNodeId ?: return
        val current = bookmarkRepository.findByUuid(currentId) as? BookmarkNode.Bookmark ?: return

        val previous = processNavigationUseCase.findPrevious(current)
        if (previous != null) {
            handleNavigateToBookmark(previous)
        } else {
            _sideEffects.emit(BookmarkSideEffect.ShowNotification("Reached start of process", NotificationType.INFORMATION))
        }
    }

    private suspend fun handleSearch(query: String, filters: Set<SearchFilter>) {
        if (query.isBlank()) {
            _state.update { it.copy(searchQuery = "", searchResults = emptyList()) }
            return
        }
        val results = bookmarkRepository.search(query).filter { node ->
            when (node) {
                is BookmarkNode.Bookmark -> filters.contains(SearchFilter.BOOKMARK)
                is BookmarkNode.Process -> filters.contains(SearchFilter.PROCESS)
                is BookmarkNode.DescriptiveBookmark -> filters.contains(SearchFilter.NOTE)
                is BookmarkNode.Group -> filters.contains(SearchFilter.GROUP)
            }
        }
        _state.update { it.copy(searchQuery = query, searchResults = results) }
    }

    private suspend fun handleClearSearch() {
        _state.update { it.copy(searchQuery = "", searchResults = emptyList()) }
    }

    private suspend fun handleExpandNode(nodeId: String) {
        _state.update { it.copy(expandedNodeIds = it.expandedNodeIds + nodeId) }
    }

    private suspend fun handleCollapseNode(nodeId: String) {
        _state.update { it.copy(expandedNodeIds = it.expandedNodeIds - nodeId) }
    }

    fun dispose() {
        scope.cancel()
    }
}

data class BookmarkViewState(
    val rootNode: BookmarkNode.Group? = null,
    val selectedNodeId: String? = null,
    val expandedNodeIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val searchResults: List<BookmarkNode> = emptyList(),
    val processProgress: ProcessProgress? = null,
    val referenceCounts: Map<String, Int> = emptyMap(),
    val referenceTargets: Set<String> = emptySet(),
    val referenceTargetsBySource: Map<String, List<String>> = emptyMap(),
    val referenceSourcesByTarget: Map<String, List<String>> = emptyMap()
)

sealed class BookmarkIntent {
    data class SelectNode(val nodeId: String) : BookmarkIntent()
    data class CreateBookmark(val parentId: String?, val bookmark: BookmarkNode.Bookmark, val insertIndex: Int?) : BookmarkIntent()
    data class CreateGroup(val parentId: String?, val group: BookmarkNode.Group, val insertIndex: Int?) : BookmarkIntent()
    data class CreateProcess(val parentId: String?, val process: BookmarkNode.Process, val insertIndex: Int?) : BookmarkIntent()
    data class CreateDescriptive(val parentId: String?, val note: BookmarkNode.DescriptiveBookmark, val insertIndex: Int?) : BookmarkIntent()
    data class EditNode(val node: BookmarkNode) : BookmarkIntent()
    data class MoveNode(val nodeId: String, val newParentId: String?, val newIndex: Int) : BookmarkIntent()
    data class DeleteNode(val nodeId: String) : BookmarkIntent()
    data class CreateReference(val sourceId: String, val targetId: String) : BookmarkIntent()
    data class SyncReferences(val sourceId: String) : BookmarkIntent()
    data class DeleteReferences(val sourceId: String) : BookmarkIntent()
    data class NavigateToBookmark(val bookmark: BookmarkNode.Bookmark) : BookmarkIntent()
    data object NavigateToNextInProcess : BookmarkIntent()
    data object NavigateToPrevInProcess : BookmarkIntent()
    data class Search(val query: String, val filters: Set<SearchFilter>) : BookmarkIntent()
    data object ClearSearch : BookmarkIntent()
    data class ExpandNode(val nodeId: String) : BookmarkIntent()
    data class CollapseNode(val nodeId: String) : BookmarkIntent()
    data object Refresh : BookmarkIntent()
}

enum class SearchFilter {
    BOOKMARK,
    PROCESS,
    NOTE,
    GROUP
}

sealed class BookmarkSideEffect {
    data class NavigateToFile(val filePath: String, val line: Int, val column: Int = 0) : BookmarkSideEffect()
    data class ShowNotification(val message: String, val type: NotificationType) : BookmarkSideEffect()
    data object ScrollToSelected : BookmarkSideEffect()
    data class SelectNode(val nodeId: String) : BookmarkSideEffect()
    data class RefreshInlays(val filePath: String) : BookmarkSideEffect()
    /** 刷新打开的 bookmarkx.json 编辑器 */
    data object RefreshBookmarkxJson : BookmarkSideEffect()
}
