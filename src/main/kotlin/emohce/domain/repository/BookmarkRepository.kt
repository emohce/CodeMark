package emohce.domain.repository

import emohce.domain.event.BookmarkEvent
import emohce.domain.model.BookmarkNode
import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {
    suspend fun getRootNode(): BookmarkNode.Group
    suspend fun findByUuid(uuid: String): BookmarkNode?
    suspend fun findByFilePath(filePath: String): List<BookmarkNode.Bookmark>
    suspend fun findParent(nodeId: String): BookmarkNode?
    /** Returns (parentId, indexAfterThis) for inserting a sibling after the given node; null if root or not found. */
    suspend fun getInsertPositionAfterNode(nodeId: String): Pair<String?, Int?>?
    suspend fun search(query: String, limit: Int = 50): List<BookmarkNode>

    suspend fun create(node: BookmarkNode, parentId: String?, index: Int? = null)
    suspend fun update(node: BookmarkNode)
    /** Updates only line (Bookmark) or entryLine (Process) for document-driven sync; emits NodeLineSynced. */
    suspend fun updateLineOnly(nodeId: String, newLine: Int)
    suspend fun delete(nodeId: String)
    suspend fun move(nodeId: String, newParentId: String?, newIndex: Int)
    suspend fun reorder(parentId: String, orderedChildIds: List<String>)

    fun observeChanges(): Flow<BookmarkEvent>
    fun observeNode(nodeId: String): Flow<BookmarkNode?>
}
