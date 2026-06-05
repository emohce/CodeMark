package emohce.presentation.toolwindow.panel

import emohce.domain.model.BookmarkNode

internal data class ContainerItem(val id: String, val label: String)

internal object BookmarkDomainTree {
    fun findNode(root: BookmarkNode, targetId: String): BookmarkNode? {
        if (root.uuid == targetId) return root
        return childrenOf(root).firstNotNullOfOrNull { findNode(it, targetId) }
    }

    fun findParent(root: BookmarkNode, nodeId: String): BookmarkNode? {
        val children = childrenOf(root)
        if (children.any { it.uuid == nodeId }) return root
        return children.firstNotNullOfOrNull { findParent(it, nodeId) }
    }

    fun pathFromRootTo(root: BookmarkNode, targetId: String): List<BookmarkNode>? {
        if (root.uuid == targetId) return listOf(root)
        for (child in childrenOf(root)) {
            val childPath = pathFromRootTo(child, targetId) ?: continue
            return listOf(root) + childPath
        }
        return null
    }

    fun existingIds(root: BookmarkNode): Set<String> {
        val ids = linkedSetOf<String>()
        traverse(root) { ids.add(it.uuid) }
        return ids
    }

    fun traverse(root: BookmarkNode, visitor: (BookmarkNode) -> Unit) {
        visitor(root)
        childrenOf(root).forEach { traverse(it, visitor) }
    }

    fun isDescendant(source: BookmarkNode, targetId: String): Boolean {
        return childrenOf(source).any { it.uuid == targetId || isDescendant(it, targetId) }
    }

    fun collectBookmarks(root: BookmarkNode): List<BookmarkNode.Bookmark> {
        val results = mutableListOf<BookmarkNode.Bookmark>()
        traverse(root) { node ->
            if (node is BookmarkNode.Bookmark) results.add(node)
        }
        return results
    }

    fun collectProcesses(root: BookmarkNode): List<BookmarkNode.Process> {
        val results = mutableListOf<BookmarkNode.Process>()
        traverse(root) { node ->
            if (node is BookmarkNode.Process) results.add(node)
        }
        return results
    }

    /** 选中 Group 下所有可展开容器（含自身及嵌套 Group/Process）的 uuid。 */
    fun collectNestedContainerIds(group: BookmarkNode.Group): Set<String> {
        val ids = linkedSetOf(group.uuid)
        fun visitContainer(node: BookmarkNode) {
            when (node) {
                is BookmarkNode.Group -> {
                    if (node.uuid != group.uuid) {
                        ids.add(node.uuid)
                    }
                    node.children.forEach { visitContainer(it) }
                }
                is BookmarkNode.Process -> {
                    ids.add(node.uuid)
                    node.steps.forEach { visitContainer(it) }
                }
                else -> Unit
            }
        }
        group.children.forEach { visitContainer(it) }
        return ids
    }

    fun withoutNodeAndDescendants(root: BookmarkNode, excludeId: String): BookmarkNode? {
        when (root) {
            is BookmarkNode.Group -> {
                if (root.uuid == excludeId) return null
                return root.copy(
                    children = root.children.mapNotNull { withoutNodeAndDescendants(it, excludeId) }
                )
            }
            is BookmarkNode.Process -> {
                if (root.uuid == excludeId) return null
                return root.copy(
                    steps = root.steps.mapNotNull { withoutNodeAndDescendants(it, excludeId) }
                )
            }
            else -> return if (root.uuid == excludeId) null else root
        }
    }

    fun expandIdsWithAncestors(root: BookmarkNode, ids: Set<String>): Set<String> {
        if (ids.isEmpty()) return ids
        val out = linkedSetOf<String>()
        ids.forEach { id ->
            val path = pathFromRootTo(root, id) ?: return@forEach
            path.forEach { out.add(it.uuid) }
        }
        return out
    }

    fun collectContainers(root: BookmarkNode, path: String = "Root"): List<ContainerItem> {
        val items = mutableListOf<ContainerItem>()
        collectContainersInto(root, path, items)
        return items
    }

    fun buildPathMap(root: BookmarkNode, path: String = "Root"): Map<String, String> {
        val paths = mutableMapOf(root.uuid to path)
        childrenOf(root).forEach { child ->
            val name = child.name.ifBlank { "(unnamed)" }
            paths.putAll(buildPathMap(child, "$path/$name"))
        }
        return paths
    }

    private fun collectContainersInto(node: BookmarkNode, path: String, items: MutableList<ContainerItem>) {
        when (node) {
            is BookmarkNode.Group -> {
                items.add(ContainerItem(node.uuid, "Group: $path"))
                node.children.forEach { child ->
                    collectContainersInto(child, "$path/${child.name}", items)
                }
            }
            is BookmarkNode.Process -> {
                items.add(ContainerItem(node.uuid, "Process: $path"))
                node.steps.forEach { child ->
                    collectContainersInto(child, "$path/${child.name}", items)
                }
            }
            else -> Unit
        }
    }

    private fun childrenOf(node: BookmarkNode): List<BookmarkNode> {
        return when (node) {
            is BookmarkNode.Group -> node.children
            is BookmarkNode.Process -> node.steps
            else -> emptyList()
        }
    }
}
