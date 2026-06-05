package emohce.presentation.toolwindow

import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import emohce.core.coroutine.CoroutineDispatchers
import emohce.data.datasource.BookmarkPersistentDataSource
import emohce.data.repository.BookmarkStore
import emohce.data.repository.BookmarkStoreProvider
import emohce.domain.event.BookmarkEvent
import emohce.domain.model.BookmarkNode
import emohce.domain.model.ProcessProgress
import emohce.domain.repository.BookmarkRepository
import emohce.domain.repository.ReferenceRepository
import emohce.domain.usecase.navigation.ProcessNavigationUseCase
import emohce.domain.usecase.reference.DetectCircularRefUseCase
import emohce.domain.usecase.reference.SyncReferencesUseCase
import emohce.presentation.index.BookmarkIndexService
import emohce.presentation.toolwindow.panel.BookmarkDomainTree
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

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
    private var pendingEditAnchorNodeId: String? = null

    private val indexService = BookmarkIndexService.getInstance(project)

    init {
        loadBookmarks()
        observeChanges()
        observeFileChanges()
    }

    fun setDocumentListener(listener: emohce.presentation.editor.BookmarkDocumentListener) {
        this.documentListener = listener
    }

    /** Returns (parentId, insertIndex) from domain model so new node is placed after the selected one. */
    suspend fun getInsertionTarget(lastSelectedNodeId: String?): Pair<String?, Int?> {
        return withContext(dispatchers.io) {
            pendingEditAnchorNodeId?.let { anchorId ->
                val anchorTarget = bookmarkRepository.getInsertPositionAfterNode(anchorId)
                if (anchorTarget != null) {
                    return@withContext anchorTarget
                }
                pendingEditAnchorNodeId = null
            }
            if (lastSelectedNodeId == null) return@withContext BookmarkStore.SUPER_ROOT_UUID to null
            val node = bookmarkRepository.findByUuid(lastSelectedNodeId) ?: return@withContext BookmarkStore.SUPER_ROOT_UUID to null
            when (node) {
                is BookmarkNode.Group -> node.uuid to 0
                is BookmarkNode.Process -> node.uuid to 0
                is BookmarkNode.Bookmark, is BookmarkNode.DescriptiveBookmark ->
                    bookmarkRepository.getInsertPositionAfterNode(lastSelectedNodeId) ?: (BookmarkStore.SUPER_ROOT_UUID to null)
                else -> BookmarkStore.SUPER_ROOT_UUID to null
            }
        }
    }

    fun markPendingEditAnchor(nodeId: String) {
        pendingEditAnchorNodeId = nodeId
    }

    fun clearPendingEditAnchor() {
        pendingEditAnchorNodeId = null
    }

    fun processIntent(intent: BookmarkIntent) {
        logger.debug("[PROCESS_INTENT] Received intent: ${intent.javaClass.simpleName}")
        scope.launch {
            try {
                when (intent) {
                    is BookmarkIntent.SelectNode -> {
                        logger.debug("[PROCESS_INTENT] Handling SelectNode: nodeId=${intent.nodeId}")
                        handleSelectNode(intent.nodeId)
                    }
                    is BookmarkIntent.CreateBookmark -> {
                        logger.debug("[PROCESS_INTENT] Handling CreateBookmark: nodeId=${intent.bookmark.uuid}, filePath=${intent.bookmark.filePath}, line=${intent.bookmark.line}")
                        handleCreateBookmark(intent.parentId, intent.bookmark, intent.insertIndex)
                    }
                is BookmarkIntent.CreateGroup -> handleCreateGroup(intent.parentId, intent.group, intent.insertIndex)
                is BookmarkIntent.CreateProcess -> handleCreateProcess(intent.parentId, intent.process, intent.insertIndex)
                is BookmarkIntent.CreateDescriptive -> handleCreateDescriptive(intent.parentId, intent.note, intent.insertIndex)
                is BookmarkIntent.EditNode -> handleEditNode(intent.node)
                is BookmarkIntent.UpdateBookmarkLineFromDocument -> handleUpdateBookmarkLineFromDocument(intent.nodeId, intent.newLine)
                is BookmarkIntent.MoveNode -> handleMoveNode(intent.nodeId, intent.newParentId, intent.newIndex)
                is BookmarkIntent.DeleteNode -> handleDeleteNode(intent.nodeId)
                is BookmarkIntent.CreateReference -> handleCreateReference(intent.sourceId, intent.targetId)
                is BookmarkIntent.SyncReferences -> handleSyncReferences(intent.sourceId)
                is BookmarkIntent.DeleteReferences -> handleDeleteReferences(intent.sourceId)
                is BookmarkIntent.NavigateToBookmark -> handleNavigateToBookmark(intent.bookmark)
                is BookmarkIntent.NavigateToNode -> handleNavigateToNode(intent.nodeId)
                is BookmarkIntent.NavigateToNextInProcess -> handleNavigateNext()
                is BookmarkIntent.NavigateToPrevInProcess -> handleNavigatePrevious()
                is BookmarkIntent.ExpandNode -> handleExpandNode(intent.nodeId)
                is BookmarkIntent.ExpandNodes -> handleExpandNodes(intent.nodeIds)
                is BookmarkIntent.CollapseNode -> handleCollapseNode(intent.nodeId)
                is BookmarkIntent.CreateRootFile -> handleCreateRootFile(intent.name)
                is BookmarkIntent.Refresh -> {
                        logger.debug("[PROCESS_INTENT] Handling Refresh")
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

    /** 从磁盘重新加载所有文件并刷新 UI（用于显式 Refresh 和外部文件变化） */
    private suspend fun reloadBookmarks() {
        logger.debug("[RELOAD_BOOKMARKS] Step 1: Starting reload...")
        _state.update { it.copy(isLoading = true, error = null) }
        try {
            if (bookmarkRepository is emohce.data.repository.BookmarkRepositoryImpl) {
                logger.debug("[RELOAD_BOOKMARKS] Step 2: Reloading store...")
                withContext(dispatchers.io) {
                    (bookmarkRepository as emohce.data.repository.BookmarkRepositoryImpl).getStore().reload()
                }
                logger.debug("[RELOAD_BOOKMARKS] Step 3: Store reloaded")
            }
            refreshStateFromStore(forceRefresh = true)
            logger.debug("[RELOAD_BOOKMARKS] Step 9: State updated, reload complete")
        } catch (e: Exception) {
            logger.error("[RELOAD_BOOKMARKS] Error: ${e.message}", e)
            _state.update { it.copy(error = e.message, isLoading = false) }
        }
    }

    /** 从内存 store 刷新 UI state（不重新读取磁盘，用于内部操作后的快速刷新） */
    private suspend fun refreshStateFromStore(
        refreshKind: TreeRefreshKind = TreeRefreshKind.DIFF,
        forceRefresh: Boolean = false
    ) {
        val root = withContext(dispatchers.io) {
            bookmarkRepository.getRootNode()
        }
        val references = withContext(dispatchers.io) {
            referenceRepository.getAllReferences()
        }
        val counts = references.groupingBy { it.sourceId }.eachCount()
        val targets = references.map { it.targetId }.toSet()
        val targetsBySource = references.groupBy({ it.sourceId }, { it.targetId })
        val sourcesByTarget = references.groupBy({ it.targetId }, { it.sourceId })
        indexService.rebuild(root, currentStoreRevision())
        val kind = if (forceRefresh) TreeRefreshKind.FULL else refreshKind
        val restoredExpanded = withContext(dispatchers.io) {
            BookmarkStoreProvider.get(project).getValidExpandedNodeIds()
        }
        _state.update {
            val expandedNodeIds = when {
                forceRefresh -> restoredExpanded
                it.expandedNodeIds.isEmpty() && restoredExpanded.isNotEmpty() -> restoredExpanded
                else -> it.expandedNodeIds
            }
            it.copy(
                rootNode = root,
                expandedNodeIds = expandedNodeIds,
                isLoading = false,
                referenceCounts = counts,
                referenceTargets = targets,
                referenceTargetsBySource = targetsBySource,
                referenceSourcesByTarget = sourcesByTarget,
                treeRefreshKind = kind,
                refreshEpoch = if (forceRefresh) it.refreshEpoch + 1 else it.refreshEpoch
            )
        }
    }

    fun ackTreeRefreshKind() {
        _state.update {
            if (it.treeRefreshKind == TreeRefreshKind.FULL) it else it.copy(treeRefreshKind = TreeRefreshKind.SKIP)
        }
    }

    private fun observeChanges() {
        scope.launch {
            bookmarkRepository.observeChanges().collect { event ->
                when (event) {
                    is BookmarkEvent.NodeAdded -> {
                        refreshStateFromStore()
                        _sideEffects.emit(BookmarkSideEffect.SelectNode(event.node.uuid))
                        val path = when (val node = event.node) {
                            is BookmarkNode.Bookmark -> node.filePath
                            is BookmarkNode.Process -> node.entryFilePath
                            else -> null
                        }
                        path?.let { 
                            refreshInlaysAndGutter(it)
                            documentListener?.onBookmarksChanged(it)
                        }
                    }
                    is BookmarkEvent.NodeUpdated -> {
                        refreshStateFromStore()
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
                    is BookmarkEvent.NodeLineSynced -> {
                        when (val node = event.node) {
                            is BookmarkNode.Bookmark -> {
                                refreshInlaysAndGutter(node.filePath)
                                documentListener?.onBookmarksChanged(node.filePath)
                            }
                            is BookmarkNode.Process -> {
                                node.entryFilePath?.let { path ->
                                    refreshInlaysAndGutter(path)
                                    documentListener?.onBookmarksChanged(path)
                                }
                            }
                            else -> Unit
                        }
                    }
                    is BookmarkEvent.NodeRemoved -> {
                        refreshStateFromStore()
                    }
                    is BookmarkEvent.NodeMoved -> {
                        refreshStateFromStore()
                    }
                    is BookmarkEvent.ReferenceSynced -> {
                        // 引用刷新可能影响提示；gutter 由 BookmarkHighlighterService 在 observeChanges 后刷新
                    }
                }
            }
        }
    }

    private suspend fun handleCreateRootFile(name: String) {
        if (bookmarkRepository is emohce.data.repository.BookmarkRepositoryImpl) {
            withContext(dispatchers.io) {
                (bookmarkRepository as emohce.data.repository.BookmarkRepositoryImpl).getStore().createNewRootFile(name)
            }
        }
        refreshStateFromStore()
        _sideEffects.emit(
            BookmarkSideEffect.ShowNotification(
                "Root file '$name' created",
                com.intellij.notification.NotificationType.INFORMATION
            )
        )
    }

    private fun observeFileChanges() {
        val basePath = project.basePath ?: return
        val codemarkDir = FileUtil.toSystemIndependentName(
            BookmarkPersistentDataSource.dataDirPath(basePath).toString()
        )
        
        val messageBus = project.messageBus.connect(scope)
        messageBus.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                var shouldReload = false
                
                for (event in events) {
                    val file = event.file ?: continue
                    val filePath = FileUtil.toSystemIndependentName(file.path)
                    
                    // 检查是否是 .codemark/ 目录下的 .json 文件变化
                    if (filePath.startsWith(codemarkDir) && filePath.endsWith(".json")) {
                        shouldReload = true
                        break
                    }
                }
                
                if (shouldReload) {
                    // 跳过自身保存触发的文件变化，避免拖拽等操作后树被完整重建导致漂移
                    if (bookmarkRepository is emohce.data.repository.BookmarkRepositoryImpl) {
                        val store = (bookmarkRepository as emohce.data.repository.BookmarkRepositoryImpl).getStore()
                        if (store.consumeSkipVfsReload()) {
                            return
                        }
                    }
                    // 外部文件变化后刷新
                    scope.launch {
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
        // Ensure document RangeMarkers are flushed so repo/index see latest lines before repaint
        documentListener?.flushForFile(path)
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

    private fun currentStoreRevision(): Long {
        return if (bookmarkRepository is emohce.data.repository.BookmarkRepositoryImpl) {
            (bookmarkRepository as emohce.data.repository.BookmarkRepositoryImpl).getStore().revision
        } else {
            -1L
        }
    }

    private suspend fun handleSelectNode(nodeId: String) {
        _state.update { it.copy(selectedNodeId = nodeId, treeRefreshKind = TreeRefreshKind.SKIP) }
    }

    private suspend fun handleCreateBookmark(parentId: String?, bookmark: BookmarkNode.Bookmark, insertIndex: Int?) {
        logger.debug("=== [CREATE_BOOKMARK_START] ===")
        logger.debug("[CREATE_BOOKMARK] Step 1: Creating bookmark - nodeId=${bookmark.uuid}, filePath=${bookmark.filePath}, line=${bookmark.line}, parentId=$parentId, insertIndex=$insertIndex")
        bookmarkRepository.create(bookmark, parentId, insertIndex)
        logger.debug("[CREATE_BOOKMARK] Step 2: Bookmark created; NodeAdded observers will refresh UI/gutter")
        logger.debug("=== [CREATE_BOOKMARK_END] ===")
    }

    private suspend fun handleCreateGroup(parentId: String?, group: BookmarkNode.Group, insertIndex: Int?) {
        bookmarkRepository.create(group, parentId, insertIndex)
    }

    private suspend fun handleCreateProcess(parentId: String?, process: BookmarkNode.Process, insertIndex: Int?) {
        bookmarkRepository.create(process, parentId, insertIndex)
    }

    private suspend fun handleCreateDescriptive(parentId: String?, note: BookmarkNode.DescriptiveBookmark, insertIndex: Int?) {
        bookmarkRepository.create(note, parentId, insertIndex)
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
        
        // 编辑后刷新打开的 codemark.json 编辑器
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

    /** Document-driven line sync only; no NavigateToFile / SelectNode / RefreshBookmarkxJson. */
    private suspend fun handleUpdateBookmarkLineFromDocument(nodeId: String, newLine: Int) {
        bookmarkRepository.updateLineOnly(nodeId, newLine)
        val node = bookmarkRepository.findByUuid(nodeId)
        val path = when (node) {
            is BookmarkNode.Bookmark -> node.filePath
            is BookmarkNode.Process -> node.entryFilePath
            else -> null
        }
        path?.let { _sideEffects.emit(BookmarkSideEffect.RefreshInlays(it)) }
    }

    private suspend fun handleMoveNode(nodeId: String, newParentId: String?, newIndex: Int) {
        if (bookmarkRepository.findByUuid(nodeId) == null) {
            if (pendingEditAnchorNodeId == nodeId) pendingEditAnchorNodeId = null
            return
        }
        bookmarkRepository.move(nodeId, newParentId, newIndex)
    }

    private suspend fun handleDeleteNode(nodeId: String) {
        if (pendingEditAnchorNodeId == nodeId) pendingEditAnchorNodeId = null
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
        // observeChanges() 会触发 BookmarkHighlighterService.rebuildIndex() → refreshOpenEditors()
        
        // 手动刷新特定文件的 gutter 与行末 line painter
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
        logger.debug("handleNavigateToBookmark: bookmark=${bookmark.uuid}, filePath=${bookmark.filePath}, line=${bookmark.line}, column=${bookmark.column}")
        clearPendingEditAnchor()
        _state.update { it.copy(selectedNodeId = bookmark.uuid, treeRefreshKind = TreeRefreshKind.SKIP) }
        logger.debug("Emitting NavigateToFile side effect")
        _sideEffects.emit(BookmarkSideEffect.NavigateToFile(bookmark.filePath, bookmark.line, bookmark.column))
        logger.debug("Emitting SelectNode side effect")
        _sideEffects.emit(BookmarkSideEffect.SelectNode(bookmark.uuid))
        logger.debug("Emitting ScrollToSelected side effect")
        _sideEffects.emit(BookmarkSideEffect.ScrollToSelected)

        val progress = processNavigationUseCase.getProgress(bookmark)
        _state.update { it.copy(processProgress = progress) }
        logger.debug("handleNavigateToBookmark completed")
    }

    private suspend fun handleNavigateToNode(nodeId: String) {
        val node = bookmarkRepository.findByUuid(nodeId) ?: return
        _state.update { it.copy(selectedNodeId = nodeId, treeRefreshKind = TreeRefreshKind.SKIP) }
        when (node) {
            is BookmarkNode.Bookmark -> {
                clearPendingEditAnchor()
                _sideEffects.emit(BookmarkSideEffect.NavigateToFile(node.filePath, node.line, node.column))
                val progress = processNavigationUseCase.getProgress(node)
                _state.update { it.copy(processProgress = progress) }
            }
            is BookmarkNode.Process -> {
                node.entryFilePath?.let { path ->
                    clearPendingEditAnchor()
                    val line = node.entryLine ?: 0
                    _sideEffects.emit(BookmarkSideEffect.NavigateToFile(path, line, 0))
                }
            }
            else -> Unit
        }
        _sideEffects.emit(BookmarkSideEffect.SelectNode(nodeId))
    }

    private suspend fun handleNavigateNext() {
        val currentId = _state.value.selectedNodeId
        if (currentId == null) {
            _sideEffects.emit(BookmarkSideEffect.ShowNotification("No selection to navigate", NotificationType.INFORMATION))
            return
        }
        val selected = bookmarkRepository.findByUuid(currentId)
        val current = when (selected) {
            is BookmarkNode.Bookmark -> selected
            is BookmarkNode.Process -> selected.flattenNavigableBookmarks().firstOrNull()
            else -> null
        }
        if (current == null) {
            _sideEffects.emit(BookmarkSideEffect.ShowNotification("Select a CodeMark in a process to navigate", NotificationType.INFORMATION))
            return
        }

        val next = processNavigationUseCase.findNext(current)
        if (next != null) {
            handleNavigateToBookmark(next)
        } else {
            _sideEffects.emit(BookmarkSideEffect.ShowNotification("Reached end of process", NotificationType.INFORMATION))
        }
    }

    private suspend fun handleNavigatePrevious() {
        val currentId = _state.value.selectedNodeId
        if (currentId == null) {
            _sideEffects.emit(BookmarkSideEffect.ShowNotification("No selection to navigate", NotificationType.INFORMATION))
            return
        }
        val selected = bookmarkRepository.findByUuid(currentId)
        val current = when (selected) {
            is BookmarkNode.Bookmark -> selected
            is BookmarkNode.Process -> selected.flattenNavigableBookmarks().lastOrNull()
            else -> null
        }
        if (current == null) {
            _sideEffects.emit(BookmarkSideEffect.ShowNotification("Select a CodeMark in a process to navigate", NotificationType.INFORMATION))
            return
        }

        val previous = processNavigationUseCase.findPrevious(current)
        if (previous != null) {
            handleNavigateToBookmark(previous)
        } else {
            _sideEffects.emit(BookmarkSideEffect.ShowNotification("Reached start of process", NotificationType.INFORMATION))
        }
    }

    private suspend fun handleExpandNode(nodeId: String) {
        val nextIds = _state.value.expandedNodeIds + nodeId
        _state.update {
            it.copy(
                expandedNodeIds = nextIds,
                treeRefreshKind = TreeRefreshKind.SKIP
            )
        }
        persistExpandedNodeIds(nextIds)
    }

    private suspend fun handleExpandNodes(nodeIds: Set<String>) {
        if (nodeIds.isEmpty()) return
        val root = _state.value.rootNode ?: return
        val merged = BookmarkDomainTree.expandIdsWithAncestors(root, nodeIds)
        val nextIds = _state.value.expandedNodeIds + merged
        _state.update {
            it.copy(
                expandedNodeIds = nextIds,
                treeRefreshKind = TreeRefreshKind.SKIP
            )
        }
        persistExpandedNodeIds(nextIds)
    }

    private suspend fun handleCollapseNode(nodeId: String) {
        val nextIds = _state.value.expandedNodeIds - nodeId
        _state.update {
            it.copy(
                expandedNodeIds = nextIds,
                treeRefreshKind = TreeRefreshKind.SKIP
            )
        }
        persistExpandedNodeIds(nextIds)
    }

    private suspend fun persistExpandedNodeIds(ids: Set<String>) {
        withContext(dispatchers.io) {
            BookmarkStoreProvider.get(project).saveExpandedNodeIds(ids)
        }
    }

    fun dispose() {
        scope.cancel()
    }
}

/** 书签树 UI 刷新粒度：避免每次 state 变化都 setRoot + 全量对齐展开 */
enum class TreeRefreshKind {
    /** 不重建模型（仅选中/高亮；或仅同步展开状态） */
    SKIP,
    /** 内存数据变更：优先 applyDiff，展开未变时不调用 applyExpandedIdsToTree */
    DIFF,
    /** 磁盘重载或 diff 失败兜底：可 setRoot + 全量展开对齐 */
    FULL
}

data class BookmarkViewState(
    val rootNode: BookmarkNode.Group? = null,
    val selectedNodeId: String? = null,
    val expandedNodeIds: Set<String> = emptySet(),
    val treeRefreshKind: TreeRefreshKind = TreeRefreshKind.SKIP,
    val refreshEpoch: Long = 0L,
    val isLoading: Boolean = false,
    val error: String? = null,
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
    /** Document-driven line sync only; no NavigateToFile / SelectNode. */
    data class UpdateBookmarkLineFromDocument(val nodeId: String, val newLine: Int) : BookmarkIntent()
    data class MoveNode(val nodeId: String, val newParentId: String?, val newIndex: Int) : BookmarkIntent()
    data class DeleteNode(val nodeId: String) : BookmarkIntent()
    data class CreateReference(val sourceId: String, val targetId: String) : BookmarkIntent()
    data class SyncReferences(val sourceId: String) : BookmarkIntent()
    data class DeleteReferences(val sourceId: String) : BookmarkIntent()
    data class NavigateToBookmark(val bookmark: BookmarkNode.Bookmark) : BookmarkIntent()
    data class NavigateToNode(val nodeId: String) : BookmarkIntent()
    data object NavigateToNextInProcess : BookmarkIntent()
    data object NavigateToPrevInProcess : BookmarkIntent()
    data class ExpandNode(val nodeId: String) : BookmarkIntent()
    data class ExpandNodes(val nodeIds: Set<String>) : BookmarkIntent()
    data class CollapseNode(val nodeId: String) : BookmarkIntent()
    data class CreateRootFile(val name: String) : BookmarkIntent()
    data object Refresh : BookmarkIntent()
}

sealed class BookmarkSideEffect {
    data class NavigateToFile(val filePath: String, val line: Int, val column: Int = 0) : BookmarkSideEffect()
    data class ShowNotification(val message: String, val type: NotificationType) : BookmarkSideEffect()
    data object ScrollToSelected : BookmarkSideEffect()
    data class SelectNode(val nodeId: String) : BookmarkSideEffect()
    data class RefreshInlays(val filePath: String) : BookmarkSideEffect()
    /** 全量刷新 gutter：清缓存 + 全量 daemon 重启 */
    data object RefreshGutterAll : BookmarkSideEffect()
    /** 按文件立即刷新 gutter（新增书签后即时展示） */
    data class RefreshGutterForFile(val filePath: String) : BookmarkSideEffect()
    /** 刷新打开的 codemark.json 编辑器 */
    data object RefreshBookmarkxJson : BookmarkSideEffect()
}
