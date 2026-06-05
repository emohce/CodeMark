package emohce.presentation.toolwindow.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Point
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import emohce.domain.model.BookmarkNode

class BookmarkTreeDropSupportTest {
    @Test
    fun `point near row edges resolves before and after with high sensitivity`() {
        val target = DefaultMutableTreeNode("target")
        val tree = treeWithTarget(target)
        val path = TreePath(target.path)
        val bounds = tree.getPathBounds(path)

        val before = BookmarkTreeDropSupport.placementForPoint(
            tree,
            Point(bounds.x + 8, bounds.y + 2),
            canDropInto = { true }
        )
        val after = BookmarkTreeDropSupport.placementForPoint(
            tree,
            Point(bounds.x + 8, bounds.y + bounds.height - 2),
            canDropInto = { true }
        )

        assertEquals(BookmarkTreeDropSupport.DropZone.BEFORE, before?.zone)
        assertEquals(BookmarkTreeDropSupport.DropZone.AFTER, after?.zone)
    }

    @Test
    fun `middle of container row resolves into target`() {
        val target = DefaultMutableTreeNode("target")
        val tree = treeWithTarget(target)
        val path = TreePath(target.path)
        val bounds = tree.getPathBounds(path)

        val placement = BookmarkTreeDropSupport.placementForPoint(
            tree,
            Point(bounds.x + 8, bounds.y + bounds.height / 2),
            canDropInto = { true }
        )

        assertEquals(BookmarkTreeDropSupport.DropZone.INTO, placement?.zone)
    }

    @Test
    fun `middle of non-container row resolves after target`() {
        val target = DefaultMutableTreeNode("target")
        val tree = treeWithTarget(target)
        val path = TreePath(target.path)
        val bounds = tree.getPathBounds(path)

        val placement = BookmarkTreeDropSupport.placementForPoint(
            tree,
            Point(bounds.x + 8, bounds.y + bounds.height / 2),
            canDropInto = { false }
        )

        assertEquals(BookmarkTreeDropSupport.DropZone.AFTER, placement?.zone)
    }

    @Test
    fun `point outside rows has no placement`() {
        val target = DefaultMutableTreeNode("target")
        val tree = treeWithTarget(target)

        val placement = BookmarkTreeDropSupport.placementForPoint(
            tree,
            Point(12, 120),
            canDropInto = { true }
        )

        assertNull(placement)
    }

    @Test
    fun `resolve before after and into targets parent and index`() {
        val parentNode = BookmarkNode.Group(uuid = "parent", name = "Parent")
        val groupNode = BookmarkNode.Group(uuid = "group", name = "Group")
        val parentTreeNode = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(parentNode, 0, false, "Parent")
        )
        val targetTreeNode = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(groupNode, 0, false, "Group")
        )
        parentTreeNode.add(targetTreeNode)

        val before = BookmarkTreeDropSupport.resolveDropTarget(
            targetTreeNode,
            groupNode,
            BookmarkTreeDropSupport.DropZone.BEFORE
        )
        val after = BookmarkTreeDropSupport.resolveDropTarget(
            targetTreeNode,
            groupNode,
            BookmarkTreeDropSupport.DropZone.AFTER
        )
        val into = BookmarkTreeDropSupport.resolveDropTarget(
            targetTreeNode,
            groupNode,
            BookmarkTreeDropSupport.DropZone.INTO
        )

        assertEquals("parent" to 0, before)
        assertEquals("parent" to 1, after)
        assertEquals("group" to -1, into)
    }

    private fun treeWithTarget(target: DefaultMutableTreeNode): JTree {
        val root = DefaultMutableTreeNode("root")
        root.add(target)
        return JTree(root).apply {
            isRootVisible = false
            setSize(240, 120)
            doLayout()
        }
    }
}
