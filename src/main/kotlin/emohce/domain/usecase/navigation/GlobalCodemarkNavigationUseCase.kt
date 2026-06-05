package emohce.domain.usecase.navigation

import emohce.domain.model.BookmarkNode
import emohce.domain.model.childNodes
import emohce.domain.repository.BookmarkRepository

/**
 * Global next/prev in tree preorder. Stops on Group / Process / Bookmark / Note (not only file-backed bookmarks).
 */
class GlobalCodemarkNavigationUseCase(
    private val bookmarkRepository: BookmarkRepository
) {
    data class NavigableEntry(
        val nodeId: String,
        val filePath: String?,
        val line: Int?,
        val column: Int = 0
    ) {
        fun hasEditorTarget(): Boolean = !filePath.isNullOrBlank() && line != null
    }

    suspend fun findNext(currentNodeId: String?): NavigableEntry? {
        val root = bookmarkRepository.getRootNode()
        val list = flattenTreeOrder(root)
        if (list.isEmpty()) return null
        val idx = list.indexOfFirst { it.nodeId == currentNodeId }
        return when {
            idx >= 0 && idx + 1 < list.size -> list[idx + 1]
            idx < 0 -> list.firstOrNull()
            else -> null
        }
    }

    suspend fun findPrevious(currentNodeId: String?): NavigableEntry? {
        val root = bookmarkRepository.getRootNode()
        val list = flattenTreeOrder(root)
        if (list.isEmpty()) return null
        val idx = list.indexOfFirst { it.nodeId == currentNodeId }
        return when {
            idx > 0 -> list[idx - 1]
            else -> null
        }
    }

    private fun flattenTreeOrder(root: BookmarkNode.Group): List<NavigableEntry> {
        val out = mutableListOf<NavigableEntry>()
        fun visit(node: BookmarkNode) {
            toEntry(node)?.let { out.add(it) }
            node.childNodes().forEach { visit(it) }
        }
        root.children.forEach { visit(it) }
        return out
    }

    private fun toEntry(node: BookmarkNode): NavigableEntry? {
        return when (node) {
            is BookmarkNode.Bookmark ->
                NavigableEntry(node.uuid, node.filePath, node.line, node.column)
            is BookmarkNode.Process -> {
                val path = node.entryFilePath
                val line = node.entryLine
                if (!path.isNullOrBlank() && line != null) {
                    NavigableEntry(node.uuid, path, line, 0)
                } else {
                    NavigableEntry(node.uuid, null, null, 0)
                }
            }
            is BookmarkNode.Group ->
                NavigableEntry(node.uuid, null, null, 0)
            is BookmarkNode.DescriptiveBookmark ->
                NavigableEntry(node.uuid, null, null, 0)
        }
    }
}
