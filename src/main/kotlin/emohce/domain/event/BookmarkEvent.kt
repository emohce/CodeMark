package emohce.domain.event

import emohce.domain.model.BookmarkNode
import java.time.Instant

sealed class BookmarkEvent {
    abstract val timestamp: Instant

    data class NodeAdded(
        val node: BookmarkNode,
        val parentId: String?,
        val index: Int,
        override val timestamp: Instant = Instant.now()
    ) : BookmarkEvent()

    data class NodeUpdated(
        val node: BookmarkNode,
        val previousNode: BookmarkNode?,
        override val timestamp: Instant = Instant.now()
    ) : BookmarkEvent()

    /** Emitted when only line/entryLine was synced from document changes; no SelectNode/NavigateToFile. */
    data class NodeLineSynced(
        val node: BookmarkNode,
        override val timestamp: Instant = Instant.now()
    ) : BookmarkEvent()

    data class NodeRemoved(
        val nodeId: String,
        val parentId: String?,
        override val timestamp: Instant = Instant.now()
    ) : BookmarkEvent()

    data class NodeMoved(
        val nodeId: String,
        val oldParentId: String?,
        val newParentId: String?,
        val newIndex: Int,
        override val timestamp: Instant = Instant.now()
    ) : BookmarkEvent()

    data class ReferenceSynced(
        val sourceId: String,
        val syncedCount: Int,
        override val timestamp: Instant = Instant.now()
    ) : BookmarkEvent()
}
