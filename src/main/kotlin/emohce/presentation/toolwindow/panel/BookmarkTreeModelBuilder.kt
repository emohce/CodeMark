package emohce.presentation.toolwindow.panel

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.util.io.FileUtil
import emohce.data.repository.BookmarkStore
import emohce.domain.model.BookmarkNode
import emohce.presentation.index.BookmarkIndexService
import emohce.presentation.toolwindow.panel.util.BookmarkTreeUtil
import javax.swing.tree.DefaultMutableTreeNode

class BookmarkTreeModelBuilder(
    private val referenceCounts: Map<String, Int>,
    private val referenceTargets: Set<String>,
    private val expandedNodeIds: Set<String>,
    private val searchQuery: String,
    private val searchResult: BookmarkIndexService.SearchResult,
    private val project: Project
) {
    fun build(rootNode: BookmarkNode.Group?): DefaultMutableTreeNode {
        if (rootNode == null) return DefaultMutableTreeNode("No data")
        val invisibleRoot = DefaultMutableTreeNode("hidden-root")
        val fileRoots = if (rootNode.uuid == BookmarkStore.SUPER_ROOT_UUID) {
            rootNode.children
        } else {
            listOf(rootNode)
        }

        if (fileRoots.size == 1) {
            addSingleRoot(invisibleRoot, fileRoots.first())
        } else {
            fileRoots.forEach { fileRoot ->
                invisibleRoot.add(buildNode(fileRoot, fileRoot.name.ifBlank { "(unnamed)" }, expandedNodeIds))
            }
        }
        return invisibleRoot
    }

    fun buildChildren(parent: BookmarkNode): List<DefaultMutableTreeNode> {
        return parent.childrenForTree()
            .map { child ->
                buildNode(
                    child,
                    child.name.ifBlank { "(unnamed)" },
                    expandedNodeIds
                )
            }
    }

    private fun addSingleRoot(parent: DefaultMutableTreeNode, singleRoot: BookmarkNode) {
        val children = singleRoot.childrenForTree()
        if (children.isEmpty()) {
            parent.add(
                buildNode(
                    singleRoot,
                    singleRoot.name.ifBlank { "(unnamed)" },
                    expandedNodeIds + singleRoot.uuid
                )
            )
            return
        }
        children.forEach { child ->
            parent.add(buildNode(child, child.name.ifBlank { "(unnamed)" }, expandedNodeIds))
        }
    }

    private fun buildNode(
        node: BookmarkNode,
        pathLabel: String,
        expandedIds: Set<String>
    ): DefaultMutableTreeNode {
        val isFileBroken = checkFileExists(node)
        val treeNode = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(
                node,
                referenceCounts[node.uuid] ?: 0,
                referenceTargets.contains(node.uuid),
                pathLabel,
                isFileBroken,
                project
            )
        )
        val children = node.childrenForTree()
        if (children.isEmpty()) return treeNode

        val shouldExpand = expandedIds.contains(node.uuid)
        if (shouldExpand) {
            children.forEach { child ->
                val childPath = "$pathLabel/${child.name.ifBlank { "(unnamed)" }}"
                treeNode.add(buildNode(child, childPath, expandedIds))
            }
        } else {
            treeNode.add(BookmarkTreeUtil.createPlaceholderNode())
        }
        return treeNode
    }

    private fun checkFileExists(node: BookmarkNode): Boolean {
        if (node !is BookmarkNode.Bookmark) return false
        val normalizedPath = FileUtil.toSystemIndependentName(node.filePath)
        val file = LocalFileSystem.getInstance().findFileByPath(normalizedPath)
        return file == null
    }

    private fun BookmarkNode.childrenForTree(): List<BookmarkNode> {
        return when (this) {
            is BookmarkNode.Group -> children
            is BookmarkNode.Process -> steps
            else -> emptyList()
        }
    }
}
