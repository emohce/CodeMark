package emohce.presentation.toolwindow.panel

import emohce.domain.model.BookmarkNode
import java.awt.Point
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

internal object BookmarkTreeDropSupport {
    private const val EDGE_ZONE_RATIO = 0.38

    enum class DropZone {
        BEFORE,
        INTO,
        AFTER
    }

    data class DropPlacement(
        val path: TreePath,
        val zone: DropZone
    )

    fun placementForPoint(
        tree: JTree,
        point: Point,
        canDropInto: (DefaultMutableTreeNode) -> Boolean
    ): DropPlacement? {
        val row = tree.getClosestRowForLocation(point.x, point.y)
        if (row < 0) return null
        val path = tree.getPathForRow(row) ?: return null
        val bounds = tree.getPathBounds(path) ?: return null
        if (point.y < bounds.y || point.y >= bounds.y + bounds.height) return null

        val ratio = (point.y - bounds.y).toDouble() / bounds.height.toDouble()
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        val zone = when {
            ratio <= EDGE_ZONE_RATIO -> DropZone.BEFORE
            ratio >= 1.0 - EDGE_ZONE_RATIO -> DropZone.AFTER
            canDropInto(node) -> DropZone.INTO
            else -> DropZone.AFTER
        }
        return DropPlacement(path, zone)
    }

    fun resolveDropTarget(
        targetNode: DefaultMutableTreeNode,
        target: BookmarkNode,
        zone: DropZone
    ): Pair<String?, Int> {
        return when (zone) {
            DropZone.INTO -> when (target) {
                is BookmarkNode.Group, is BookmarkNode.Process -> target.uuid to -1
                else -> siblingTarget(targetNode, after = true)
            }
            DropZone.BEFORE -> siblingTarget(targetNode, after = false)
            DropZone.AFTER -> siblingTarget(targetNode, after = true)
        }
    }

    private fun siblingTarget(targetNode: DefaultMutableTreeNode, after: Boolean): Pair<String?, Int> {
        val parentNode = targetNode.parent as? DefaultMutableTreeNode ?: return null to -1
        val parent = (parentNode.userObject as? BookmarkPanel.NodeView)?.node ?: return null to -1
        val targetIndex = parentNode.getIndex(targetNode).takeIf { it >= 0 } ?: return null to -1
        return parent.uuid to if (after) targetIndex + 1 else targetIndex
    }
}
