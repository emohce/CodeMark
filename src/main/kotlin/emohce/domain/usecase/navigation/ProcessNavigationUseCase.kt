package emohce.domain.usecase.navigation

import emohce.domain.model.BookmarkNode
import emohce.domain.model.ProcessProgress
import emohce.domain.repository.BookmarkRepository

class ProcessNavigationUseCase(
    private val bookmarkRepository: BookmarkRepository
) {
    suspend fun findNext(current: BookmarkNode.Bookmark): BookmarkNode.Bookmark? {
        val process = findParentProcess(current.uuid) ?: return null
        val steps = process.flattenNavigableBookmarks()
        val currentIndex = steps.indexOfFirst { it.uuid == current.uuid }
        return steps.getOrNull(currentIndex + 1)
    }

    suspend fun findPrevious(current: BookmarkNode.Bookmark): BookmarkNode.Bookmark? {
        val process = findParentProcess(current.uuid) ?: return null
        val steps = process.flattenNavigableBookmarks()
        val currentIndex = steps.indexOfFirst { it.uuid == current.uuid }
        return steps.getOrNull(currentIndex - 1)
    }

    suspend fun getProgress(current: BookmarkNode.Bookmark): ProcessProgress? {
        val process = findParentProcess(current.uuid) ?: return null
        val steps = process.flattenNavigableBookmarks()
        val currentIndex = steps.indexOfFirst { it.uuid == current.uuid }
        return if (currentIndex >= 0) {
            ProcessProgress(
                processName = process.name,
                currentStep = currentIndex + 1,
                totalSteps = steps.size,
                currentBookmark = current
            )
        } else {
            null
        }
    }

    private suspend fun findParentProcess(nodeId: String): BookmarkNode.Process? {
        val root = bookmarkRepository.getRootNode()
        return findProcessContaining(root, nodeId)
    }

    private fun findProcessContaining(node: BookmarkNode, targetId: String): BookmarkNode.Process? {
        return when (node) {
            is BookmarkNode.Bookmark -> null
            is BookmarkNode.DescriptiveBookmark -> null
            is BookmarkNode.Group -> node.children.firstNotNullOfOrNull { findProcessContaining(it, targetId) }
            is BookmarkNode.Process -> {
                if (containsNode(node, targetId)) node
                else node.steps.firstNotNullOfOrNull { findProcessContaining(it, targetId) }
            }
        }
    }

    private fun containsNode(process: BookmarkNode.Process, targetId: String): Boolean {
        return process.steps.any { step ->
            when (step) {
                is BookmarkNode.Bookmark -> step.uuid == targetId
                is BookmarkNode.DescriptiveBookmark -> step.uuid == targetId
                is BookmarkNode.Process -> containsNode(step, targetId)
                else -> false
            }
        }
    }
}
