package emohce.domain.usecase.navigation

import emohce.domain.model.BookmarkNode
import emohce.domain.repository.BookmarkRepository

/**
 * Global next/prev CodeMark in tree preorder. Navigable = Bookmark or Process (by entry).
 * Plan: current is Group/Process -> next = first inside (or next after); current is Bookmark -> next = sibling next (or recurse).
 */
class GlobalCodemarkNavigationUseCase(
    private val bookmarkRepository: BookmarkRepository
) {
    data class NavigableEntry(
        val nodeId: String,
        val filePath: String,
        val line: Int,
        val column: Int = 0
    )

    suspend fun findNext(currentNodeId: String?): NavigableEntry? {
        val root = bookmarkRepository.getRootNode()
        val list = flattenNavigable(root)
        if (list.isEmpty()) return null
        val idx = list.indexOfFirst { it.nodeId == currentNodeId }
        if (idx >= 0) {
            return if (idx + 1 < list.size) list[idx + 1] else null
        }
        val node = bookmarkRepository.findByUuid(currentNodeId ?: return list.firstOrNull())
        if (node is BookmarkNode.Group || node is BookmarkNode.Process) {
            findFirstNavigableInside(node)?.let { return it }
            val withContainer = flattenNavigableWithContainer(root)
            val lastInside = withContainer.indexOfLast { it.second == currentNodeId }
            if (lastInside >= 0 && lastInside + 1 < withContainer.size) return withContainer[lastInside + 1].first
        }
        return list.firstOrNull()
    }

    suspend fun findPrevious(currentNodeId: String?): NavigableEntry? {
        val root = bookmarkRepository.getRootNode()
        val list = flattenNavigable(root)
        if (list.isEmpty()) return null
        val idx = list.indexOfFirst { it.nodeId == currentNodeId }
        if (idx >= 0) {
            return if (idx > 0) list[idx - 1] else null
        }
        val node = bookmarkRepository.findByUuid(currentNodeId ?: return null)
        if (node is BookmarkNode.Group || node is BookmarkNode.Process) {
            val withContainer = flattenNavigableWithContainer(root)
            val firstInside = withContainer.indexOfFirst { it.second == currentNodeId }
            if (firstInside > 0) return withContainer[firstInside - 1].first
            return null
        }
        return null
    }

    private fun findFirstNavigableInside(node: BookmarkNode): NavigableEntry? {
        return when (node) {
            is BookmarkNode.Bookmark ->
                NavigableEntry(node.uuid, node.filePath, node.line, node.column)
            is BookmarkNode.Process -> {
                val path = node.entryFilePath
                val line = node.entryLine ?: 0
                if (!path.isNullOrBlank()) NavigableEntry(node.uuid, path, line, 0)
                else node.steps.asSequence().mapNotNull { findFirstNavigableInside(it) }.firstOrNull()
            }
            is BookmarkNode.Group ->
                node.children.asSequence().mapNotNull { findFirstNavigableInside(it) }.firstOrNull()
            is BookmarkNode.DescriptiveBookmark -> null
        }
    }

    private fun flattenNavigable(root: BookmarkNode.Group): List<NavigableEntry> {
        val out = mutableListOf<NavigableEntry>()
        fun visit(node: BookmarkNode) {
            when (node) {
                is BookmarkNode.Bookmark ->
                    out.add(NavigableEntry(node.uuid, node.filePath, node.line, node.column))
                is BookmarkNode.Process -> {
                    val path = node.entryFilePath
                    val line = node.entryLine ?: 0
                    if (!path.isNullOrBlank()) {
                        out.add(NavigableEntry(node.uuid, path, line, 0))
                    }
                    node.steps.forEach { visit(it) }
                }
                is BookmarkNode.Group ->
                    node.children.forEach { visit(it) }
                is BookmarkNode.DescriptiveBookmark -> Unit
            }
        }
        root.children.forEach { visit(it) }
        return out
    }

    private fun flattenNavigableWithContainer(root: BookmarkNode.Group): List<Pair<NavigableEntry, String?>> {
        val out = mutableListOf<Pair<NavigableEntry, String?>>()
        fun visit(node: BookmarkNode, containerId: String?) {
            when (node) {
                is BookmarkNode.Bookmark ->
                    out.add(NavigableEntry(node.uuid, node.filePath, node.line, node.column) to containerId)
                is BookmarkNode.Process -> {
                    val path = node.entryFilePath
                    val line = node.entryLine ?: 0
                    if (!path.isNullOrBlank()) {
                        out.add(NavigableEntry(node.uuid, path, line, 0) to containerId)
                    }
                    node.steps.forEach { visit(it, node.uuid) }
                }
                is BookmarkNode.Group ->
                    node.children.forEach { visit(it, node.uuid) }
                is BookmarkNode.DescriptiveBookmark -> Unit
            }
        }
        root.children.forEach { visit(it, root.uuid) }
        return out
    }
}
