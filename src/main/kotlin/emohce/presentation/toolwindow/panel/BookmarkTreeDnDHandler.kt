package emohce.presentation.toolwindow.panel

import emohce.data.repository.BookmarkStore
import emohce.domain.model.BookmarkNode
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.TransferHandler
import javax.swing.tree.DefaultMutableTreeNode

internal class BookmarkTreeDnDHandler(
    private val selectedNode: () -> BookmarkNode?,
    private val currentRoot: () -> BookmarkNode?,
    private val isDescendant: (BookmarkNode, String) -> Boolean,
    private val currentDropPlacement: () -> BookmarkTreeDropSupport.DropPlacement?,
    private val moveNode: (nodeId: String, parentId: String, index: Int) -> Unit
) : TransferHandler() {
    override fun getSourceActions(c: JComponent): Int = MOVE

    override fun exportAsDrag(c: JComponent, e: InputEvent, action: Int) {
        if (c is JTree && e is MouseEvent) {
            c.requestFocusInWindow()
            c.getPathForLocation(e.x, e.y)?.let { path ->
                c.selectionPath = path
                c.scrollPathToVisible(path)
            }
        }
        super.exportAsDrag(c, e, action)
    }

    override fun createTransferable(c: JComponent): Transferable? {
        val node = nodeAtDragSource(c) ?: return null
        if (node.uuid == BookmarkStore.SUPER_ROOT_UUID) return null
        return StringSelection(node.uuid)
    }

    private fun nodeAtDragSource(c: JComponent): BookmarkNode? {
        val tree = c as? JTree ?: return selectedNode()
        val treeNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return (treeNode.userObject as? BookmarkPanel.NodeView)?.node
    }

    override fun canImport(support: TransferSupport): Boolean {
        if (!support.isDrop) return false
        if (!support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false
        val dropLocation = support.dropLocation as? javax.swing.JTree.DropLocation ?: return false
        val path = dropLocation.path ?: return false
        val targetNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return false
        val target = (targetNode.userObject as? BookmarkPanel.NodeView)?.node
        return target != null
    }

    override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        val nodeId = support.transferable.getTransferData(DataFlavor.stringFlavor) as? String ?: return false
        val dropLocation = support.dropLocation as? javax.swing.JTree.DropLocation ?: return false
        val path = dropLocation.path ?: return false
        val targetNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return false
        val target = (targetNode.userObject as? BookmarkPanel.NodeView)?.node ?: return false

        val placement = currentDropPlacement()?.takeIf { it.path == path }
        val zone = placement?.zone ?: fallbackDropZone(dropLocation.childIndex, target)
        val (parentId, rawIndex) = BookmarkTreeDropSupport.resolveDropTarget(targetNode, target, zone)
        if (parentId == null) return false
        if (parentId == BookmarkStore.SUPER_ROOT_UUID) return false
        if (nodeId == parentId) return false

        val root = currentRoot() ?: return false
        val movedNode = BookmarkDomainTree.findNode(root, nodeId) ?: return false
        if (isDescendant(movedNode, parentId)) return false

        val index = normalizeDropIndex(targetNode, nodeId, rawIndex)
        moveNode(nodeId, parentId, index)
        return true
    }

    private fun fallbackDropZone(
        childIndex: Int,
        target: BookmarkNode
    ): BookmarkTreeDropSupport.DropZone {
        if (childIndex >= 0) return BookmarkTreeDropSupport.DropZone.BEFORE
        return when (target) {
            is BookmarkNode.Group, is BookmarkNode.Process -> BookmarkTreeDropSupport.DropZone.INTO
            else -> BookmarkTreeDropSupport.DropZone.AFTER
        }
    }

    private fun normalizeDropIndex(
        targetNode: DefaultMutableTreeNode,
        nodeId: String,
        rawIndex: Int
    ): Int {
        if (rawIndex < 0) return rawIndex
        val targetParent = targetNode.parent as? DefaultMutableTreeNode ?: return rawIndex
        val treeRoot = targetNode.root as? DefaultMutableTreeNode ?: return rawIndex
        val sourceNode = findTreeNode(treeRoot, nodeId) ?: return rawIndex
        val sourceParent = sourceNode.parent as? DefaultMutableTreeNode ?: return rawIndex
        if (sourceParent !== targetParent) return rawIndex
        val sourceIndex = sourceParent.getIndex(sourceNode)
        return if (sourceIndex in 0 until rawIndex) rawIndex - 1 else rawIndex
    }

    private fun findTreeNode(root: DefaultMutableTreeNode, nodeId: String): DefaultMutableTreeNode? {
        val view = root.userObject as? BookmarkPanel.NodeView
        if (view?.node?.uuid == nodeId) return root
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val found = findTreeNode(child, nodeId)
            if (found != null) return found
        }
        return null
    }

}
