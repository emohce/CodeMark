package emohce.data.repository

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileUtil
import emohce.domain.event.BookmarkEvent
import emohce.domain.model.BookmarkNode
import emohce.domain.model.childNodes
import emohce.domain.model.searchableText
import emohce.domain.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class BookmarkRepositoryImpl(private val store: BookmarkStore) : BookmarkRepository {
    private val logger = Logger.getInstance(BookmarkRepositoryImpl::class.java)
    private val changes = MutableSharedFlow<BookmarkEvent>(extraBufferCapacity = 64)
    private val nodeStates = ConcurrentHashMap<String, MutableStateFlow<BookmarkNode?>>()
    private var indexedRevision: Long = -1L
    private var nodeById: Map<String, BookmarkNode> = emptyMap()
    private var parentById: Map<String, BookmarkNode> = emptyMap()
    private var bookmarksByFile: Map<String, List<BookmarkNode.Bookmark>> = emptyMap()
    
    fun getStore(): BookmarkStore = store

    override suspend fun getRootNode(): BookmarkNode.Group {
        ensureIndex()
        return store.root
    }

    override suspend fun findByUuid(uuid: String): BookmarkNode? {
        ensureIndex()
        return nodeById[uuid]
    }

    override suspend fun findByFilePath(filePath: String): List<BookmarkNode.Bookmark> {
        ensureIndex()
        return bookmarksByFile[FileUtil.toSystemIndependentName(filePath)].orEmpty()
    }

    override suspend fun findParent(nodeId: String): BookmarkNode? {
        ensureIndex()
        return parentById[nodeId]
    }

    override suspend fun getInsertPositionAfterNode(nodeId: String): Pair<String?, Int?>? {
        if (nodeId == BookmarkStore.SUPER_ROOT_UUID) return null
        ensureIndex()
        val parent = parentById[nodeId] ?: return null
        val idx = when (parent) {
            is BookmarkNode.Group -> parent.children.indexOfFirst { it.uuid == nodeId }
            is BookmarkNode.Process -> parent.steps.indexOfFirst { it.uuid == nodeId }
            else -> -1
        }
        return if (idx >= 0) parent.uuid to (idx + 1) else null
    }

    override suspend fun search(query: String, limit: Int): List<BookmarkNode> {
        if (query.isBlank()) return emptyList()
        ensureIndex()
        val normalized = query.trim()
        val results = mutableListOf<BookmarkNode>()
        for (node in nodeById.values) {
            if (results.size >= limit) break
            if (node.searchableText().contains(normalized, ignoreCase = true)) {
                results.add(node)
            }
        }
        return results
    }

    override suspend fun create(node: BookmarkNode, parentId: String?, index: Int?) {
        // 如果 parentId 是超级根，重定向到第一个文件根
        val effectiveParentId = if (parentId == null || parentId == BookmarkStore.SUPER_ROOT_UUID) {
            store.firstFileRootId() ?: parentId
        } else parentId
        logger.debug("[REPO_CREATE] Step 1: Creating node - uuid=${node.uuid}, type=${node.javaClass.simpleName}, parentId=$effectiveParentId, index=$index")
        if (node is BookmarkNode.Bookmark) {
            logger.debug("[REPO_CREATE] Bookmark details: filePath=${node.filePath}, line=${node.line}, column=${node.column}, name=${node.name}")
        }
        
        if (!store.insertNode(effectiveParentId, node, index)) return
        logger.debug("[REPO_CREATE] Step 2: Node inserted into store")
        
        changes.tryEmit(BookmarkEvent.NodeAdded(node, parentId, index ?: -1))
        logger.debug("[REPO_CREATE] Step 3: NodeAdded event emitted")
        
        notifyObserved(node.uuid)
        logger.debug("[REPO_CREATE] Step 4: Node state notified, create complete")
    }

    override suspend fun update(node: BookmarkNode) {
        ensureIndex()
        val previous = store.updateNode(node) ?: return
        changes.tryEmit(BookmarkEvent.NodeUpdated(node, previous))
        notifyObserved(node.uuid)
    }

    override suspend fun updateLineOnly(nodeId: String, newLine: Int) {
        ensureIndex()
        val node = nodeById[nodeId] ?: return
        val updated = when (node) {
            is BookmarkNode.Bookmark -> node.copy(line = newLine)
            is BookmarkNode.Process -> node.copy(entryLine = newLine)
            else -> return
        }
        store.updateNode(updated) ?: return
        changes.tryEmit(BookmarkEvent.NodeLineSynced(updated))
        notifyObserved(nodeId)
    }

    override suspend fun delete(nodeId: String) {
        ensureIndex()
        val parentId = store.deleteNode(nodeId) ?: return
        changes.tryEmit(BookmarkEvent.NodeRemoved(nodeId, parentId))
        notifyObserved(nodeId)
    }

    override suspend fun move(nodeId: String, newParentId: String?, newIndex: Int) {
        ensureIndex()
        if (!store.moveNode(nodeId, newParentId, newIndex)) return
        changes.tryEmit(BookmarkEvent.NodeMoved(nodeId, null, newParentId, newIndex))
        notifyObserved(nodeId)
    }

    override suspend fun reorder(parentId: String, orderedChildIds: List<String>) {
        store.reorderChildren(parentId, orderedChildIds)
    }

    override fun observeChanges(): Flow<BookmarkEvent> {
        return changes
    }

    override fun observeNode(nodeId: String): Flow<BookmarkNode?> {
        val state = nodeStates.computeIfAbsent(nodeId) {
            ensureIndex()
            MutableStateFlow(nodeById[nodeId])
        }
        return state.asStateFlow()
    }

    private fun notifyObserved(nodeId: String) {
        ensureIndex()
        nodeStates[nodeId]?.value = nodeById[nodeId]
    }

    private fun ensureIndex() {
        val currentRevision = store.revision
        if (indexedRevision == currentRevision) return
        val currentRoot = store.root
        val nodes = linkedMapOf<String, BookmarkNode>()
        val parents = linkedMapOf<String, BookmarkNode>()
        val byFile = linkedMapOf<String, MutableList<BookmarkNode.Bookmark>>()
        fun visit(node: BookmarkNode, parent: BookmarkNode?) {
            nodes[node.uuid] = node
            if (parent != null) parents[node.uuid] = parent
            if (node is BookmarkNode.Bookmark) {
                val path = FileUtil.toSystemIndependentName(node.filePath)
                byFile.getOrPut(path) { mutableListOf() }.add(node)
            }
            node.childNodes().forEach { child -> visit(child, node) }
        }
        visit(currentRoot, null)
        nodeById = nodes
        parentById = parents
        bookmarksByFile = byFile
        indexedRevision = currentRevision
    }

}
