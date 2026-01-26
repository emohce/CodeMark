package emohce.data.repository

import com.intellij.openapi.diagnostic.Logger
import emohce.domain.event.BookmarkEvent
import emohce.domain.model.BookmarkNode
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
    
    fun getStore(): BookmarkStore = store

    override suspend fun getRootNode(): BookmarkNode.Group {
        return store.root
    }

    override suspend fun findByUuid(uuid: String): BookmarkNode? {
        return findByUuidInternal(store.root, uuid)
    }

    override suspend fun findByFilePath(filePath: String): List<BookmarkNode.Bookmark> {
        val results = mutableListOf<BookmarkNode.Bookmark>()
        traverse(store.root) { node ->
            if (node is BookmarkNode.Bookmark && node.filePath == filePath) {
                results.add(node)
            }
        }
        return results
    }

    override suspend fun findParent(nodeId: String): BookmarkNode? {
        return findParentInternal(store.root, nodeId)
    }

    override suspend fun search(query: String, limit: Int): List<BookmarkNode> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<BookmarkNode>()
        traverse(store.root) { node ->
            if (results.size >= limit) return@traverse
            val match = node.name.contains(query, ignoreCase = true) ||
                node.description.contains(query, ignoreCase = true)
            if (match) results.add(node)
        }
        return results
    }

    override suspend fun create(node: BookmarkNode, parentId: String?, index: Int?) {
        logger.info("[REPO_CREATE] Step 1: Creating node - uuid=${node.uuid}, type=${node.javaClass.simpleName}, parentId=$parentId, index=$index")
        if (node is BookmarkNode.Bookmark) {
            logger.info("[REPO_CREATE] Bookmark details: filePath=${node.filePath}, line=${node.line}, column=${node.column}, name=${node.name}")
        }
        
        val root = insertChild(store.root, parentId, node, index)
        logger.info("[REPO_CREATE] Step 2: Node inserted into tree, root uuid=${root.uuid}")
        
        store.replaceRoot(root)
        logger.info("[REPO_CREATE] Step 3: Root replaced in store")
        
        changes.tryEmit(BookmarkEvent.NodeAdded(node, parentId, index ?: -1))
        logger.info("[REPO_CREATE] Step 4: NodeAdded event emitted")
        
        notifyObserved(node.uuid)
        logger.info("[REPO_CREATE] Step 5: Node state notified, create complete")
    }

    override suspend fun update(node: BookmarkNode) {
        val previous = findByUuidInternal(store.root, node.uuid)
        val root = replaceNode(store.root, node) as BookmarkNode.Group
        store.replaceRoot(root)
        changes.tryEmit(BookmarkEvent.NodeUpdated(node, previous))
        notifyObserved(node.uuid)
    }

    override suspend fun delete(nodeId: String) {
        val parent = findParentInternal(store.root, nodeId)
        val root = removeNode(store.root, nodeId)
        store.replaceRoot(root)
        changes.tryEmit(BookmarkEvent.NodeRemoved(nodeId, parent?.uuid))
        notifyObserved(nodeId)
    }

    override suspend fun move(nodeId: String, newParentId: String?, newIndex: Int) {
        val node = findByUuidInternal(store.root, nodeId) ?: return
        val rootRemoved = removeNode(store.root, nodeId)
        val rootInserted = insertChild(rootRemoved, newParentId, node, newIndex)
        store.replaceRoot(rootInserted)
        changes.tryEmit(BookmarkEvent.NodeMoved(nodeId, null, newParentId, newIndex))
        notifyObserved(nodeId)
    }

    override suspend fun reorder(parentId: String, orderedChildIds: List<String>) {
        val root = reorderChildren(store.root, parentId, orderedChildIds) as BookmarkNode.Group
        store.replaceRoot(root)
    }

    override fun observeChanges(): Flow<BookmarkEvent> {
        return changes
    }

    override fun observeNode(nodeId: String): Flow<BookmarkNode?> {
        val state = nodeStates.computeIfAbsent(nodeId) {
            MutableStateFlow(findByUuidInternal(store.root, nodeId))
        }
        return state.asStateFlow()
    }

    private fun notifyObserved(nodeId: String) {
        nodeStates[nodeId]?.value = findByUuidInternal(store.root, nodeId)
    }

    private fun traverse(node: BookmarkNode, visitor: (BookmarkNode) -> Unit) {
        visitor(node)
        when (node) {
            is BookmarkNode.Group -> node.children.forEach { traverse(it, visitor) }
            is BookmarkNode.Process -> node.steps.forEach { traverse(it, visitor) }
            else -> Unit
        }
    }

    private fun findByUuidInternal(node: BookmarkNode, targetId: String): BookmarkNode? {
        if (node.uuid == targetId) return node
        return when (node) {
            is BookmarkNode.Group -> node.children.firstNotNullOfOrNull { findByUuidInternal(it, targetId) }
            is BookmarkNode.Process -> node.steps.firstNotNullOfOrNull { findByUuidInternal(it, targetId) }
            else -> null
        }
    }

    private fun findParentInternal(node: BookmarkNode, targetId: String): BookmarkNode? {
        when (node) {
            is BookmarkNode.Group -> {
                if (node.children.any { it.uuid == targetId }) return node
                return node.children.firstNotNullOfOrNull { findParentInternal(it, targetId) }
            }
            is BookmarkNode.Process -> {
                if (node.steps.any { it.uuid == targetId }) return node
                return node.steps.firstNotNullOfOrNull { findParentInternal(it, targetId) }
            }
            else -> return null
        }
    }

    private fun insertChild(
        current: BookmarkNode,
        parentId: String?,
        child: BookmarkNode,
        index: Int?
    ): BookmarkNode.Group {
        val targetId = parentId ?: current.uuid
        val updated = insertChildInternal(current, targetId, child, index)
        return updated as BookmarkNode.Group
    }

    private fun insertChildInternal(
        current: BookmarkNode,
        parentId: String,
        child: BookmarkNode,
        index: Int?
    ): BookmarkNode {
        return when (current) {
            is BookmarkNode.Group -> {
                if (current.uuid == parentId) {
                    current.copy(children = insertAt(current.children, child, index))
                } else {
                    current.copy(children = current.children.map { insertChildInternal(it, parentId, child, index) })
                }
            }
            is BookmarkNode.Process -> {
                if (current.uuid == parentId) {
                    current.copy(steps = insertAt(current.steps, child, index))
                } else {
                    current.copy(steps = current.steps.map { insertChildInternal(it, parentId, child, index) })
                }
            }
            else -> current
        }
    }

    private fun replaceNode(current: BookmarkNode, updated: BookmarkNode): BookmarkNode {
        if (current.uuid == updated.uuid) return updated
        return when (current) {
            is BookmarkNode.Group -> current.copy(children = current.children.map { replaceNode(it, updated) })
            is BookmarkNode.Process -> current.copy(steps = current.steps.map { replaceNode(it, updated) })
            else -> current
        }
    }

    private fun removeNode(current: BookmarkNode, targetId: String): BookmarkNode.Group {
        val updated = removeNodeInternal(current, targetId)
        return updated as BookmarkNode.Group
    }

    private fun removeNodeInternal(current: BookmarkNode, targetId: String): BookmarkNode {
        return when (current) {
            is BookmarkNode.Group -> current.copy(
                children = current.children
                    .filterNot { it.uuid == targetId }
                    .map { removeNodeInternal(it, targetId) }
            )
            is BookmarkNode.Process -> current.copy(
                steps = current.steps
                    .filterNot { it.uuid == targetId }
                    .map { removeNodeInternal(it, targetId) }
            )
            else -> current
        }
    }

    private fun reorderChildren(
        current: BookmarkNode,
        parentId: String,
        orderedChildIds: List<String>
    ): BookmarkNode {
        return when (current) {
            is BookmarkNode.Group -> {
                if (current.uuid == parentId) {
                    val reordered = orderedChildIds.mapNotNull { id ->
                        current.children.firstOrNull { it.uuid == id }
                    }
                    current.copy(children = reordered)
                } else {
                    current.copy(children = current.children.map { reorderChildren(it, parentId, orderedChildIds) })
                }
            }
            is BookmarkNode.Process -> {
                if (current.uuid == parentId) {
                    val reordered = orderedChildIds.mapNotNull { id ->
                        current.steps.firstOrNull { it.uuid == id }
                    }
                    current.copy(steps = reordered)
                } else {
                    current.copy(steps = current.steps.map { reorderChildren(it, parentId, orderedChildIds) })
                }
            }
            else -> current
        }
    }

    private fun <T> insertAt(list: List<T>, item: T, index: Int?): List<T> {
        if (index == null || index < 0 || index >= list.size) {
            return list + item
        }
        val mutable = list.toMutableList()
        mutable.add(index, item)
        return mutable.toList()
    }
}
