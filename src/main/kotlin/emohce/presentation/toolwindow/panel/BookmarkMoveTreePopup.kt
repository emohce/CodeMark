package emohce.presentation.toolwindow.panel

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import emohce.data.repository.BookmarkStore
import emohce.domain.model.BookmarkNode
import emohce.presentation.index.BookmarkIndexService
import emohce.presentation.toolwindow.panel.render.BookmarkTreeCellRenderer
import emohce.presentation.toolwindow.panel.util.BookmarkTreeUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

internal class BookmarkMoveTreePopup(
    private val project: Project,
    private val displayRoot: BookmarkNode.Group,
    private val movingNode: BookmarkNode,
    private val referenceCounts: Map<String, Int>,
    private val referenceTargets: Set<String>,
    private val expandedNodeIds: Set<String>,
    private val onConfirm: (parentId: String, index: Int) -> Unit
) {
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Loading"))
    private val tree = Tree(treeModel)
    private val renderer = BookmarkTreeCellRenderer()
    private val placementLabel = JLabel()
    private var placement: BookmarkTreeDropSupport.DropPlacement? = null
    private var dropZone: BookmarkTreeDropSupport.DropZone = BookmarkTreeDropSupport.DropZone.AFTER

    fun show(anchor: JComponent): Boolean {
        buildTree()
        val header = JPanel(BorderLayout()).apply {
            add(
                JLabel("↑↓ 选择目标行，←→ 调整插入位置（前/内/后），Enter 确认").apply {
                    border = JBUI.Borders.emptyBottom(4)
                },
                BorderLayout.NORTH
            )
            add(placementLabel, BorderLayout.SOUTH)
        }
        val content = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(header, BorderLayout.NORTH)
            add(
                JBScrollPane(tree).apply {
                    preferredSize = Dimension(420, 320)
                },
                BorderLayout.CENTER
            )
        }
        var confirmed = false
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, tree)
            .setTitle("移动")
            .setResizable(true)
            .setRequestFocus(true)
            .setModalContext(true)
            .setCancelOnClickOutside(false)
            .setCancelOnWindowDeactivation(false)
            .createPopup()
        installTreeKeys(popup) { confirmed = true }
        val okButton = JButton("确定").apply {
            addActionListener {
                if (commitPlacement()) {
                    confirmed = true
                    popup.closeOk(null)
                }
            }
        }
        val cancelButton = JButton("取消").apply {
            addActionListener { popup.cancel() }
        }
        val buttons = JPanel().apply {
            add(okButton)
            add(cancelButton)
        }
        content.add(buttons, BorderLayout.SOUTH)
        applyDefaultPlacement()
        popup.show(RelativePoint.getCenterOf(anchor))
        return confirmed
    }

    private fun buildTree() {
        val allExpand = BookmarkDomainTree.collectContainers(displayRoot).map { it.id }.toSet()
        val expanded = BookmarkDomainTree.expandIdsWithAncestors(displayRoot, allExpand + expandedNodeIds)
        val invisibleRoot = BookmarkTreeModelBuilder(
            referenceCounts = referenceCounts,
            referenceTargets = referenceTargets,
            expandedNodeIds = expanded,
            searchQuery = "",
            searchResult = BookmarkIndexService.SearchResult.EMPTY,
            project = project
        ).build(displayRoot)
        treeModel.setRoot(invisibleRoot)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = renderer
        tree.rowHeight = 0
    }

    private fun applyDefaultPlacement() {
        val parent = BookmarkDomainTree.findParent(displayRoot, movingNode.uuid) ?: return
        val siblings = when (parent) {
            is BookmarkNode.Group -> parent.children
            is BookmarkNode.Process -> parent.steps
            else -> return
        }
        val selfIndex = siblings.indexOfFirst { it.uuid == movingNode.uuid }
        if (selfIndex < 0) return
        val (path, zone) = when (movingNode) {
            is BookmarkNode.Group -> {
                val parentPath = findDomainPath(parent.uuid) ?: return
                parentPath to BookmarkTreeDropSupport.DropZone.INTO
            }
            else -> {
                val afterIndex = selfIndex + 1
                if (afterIndex < siblings.size) {
                    val nextPath = findDomainPath(siblings[afterIndex].uuid) ?: return
                    nextPath to BookmarkTreeDropSupport.DropZone.BEFORE
                } else if (selfIndex > 0) {
                    val prevPath = findDomainPath(siblings[selfIndex - 1].uuid) ?: return
                    prevPath to BookmarkTreeDropSupport.DropZone.AFTER
                } else {
                    val parentPath = findDomainPath(parent.uuid) ?: return
                    parentPath to BookmarkTreeDropSupport.DropZone.INTO
                }
            }
        }
        selectPlacement(path, zone)
    }

    private fun findDomainPath(nodeId: String): TreePath? {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return null
        val treeNode = BookmarkTreeUtil.findNodeById(root, nodeId) ?: return null
        return TreePath(treeNode.path)
    }

    private fun selectPlacement(path: TreePath, zone: BookmarkTreeDropSupport.DropZone) {
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        dropZone = zone
        placement = BookmarkTreeDropSupport.DropPlacement(path, zone)
        updatePlacementLabel()
    }

    private fun updatePlacementLabel() {
        val path = placement?.path ?: tree.selectionPath
        val zone = placement?.zone ?: dropZone
        val targetNode = path?.lastPathComponent as? DefaultMutableTreeNode
        val target = (targetNode?.userObject as? BookmarkPanel.NodeView)?.node
        val zoneText = when (zone) {
            BookmarkTreeDropSupport.DropZone.BEFORE -> "之前"
            BookmarkTreeDropSupport.DropZone.INTO -> "内部（末尾）"
            BookmarkTreeDropSupport.DropZone.AFTER -> "之后"
        }
        val name = target?.name?.ifBlank { "(未命名)" } ?: "—"
        placementLabel.text = "插入位置：$name（$zoneText）"
    }

    private fun cycleDropZone(delta: Int) {
        val path = tree.selectionPath ?: return
        val targetNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val target = (targetNode.userObject as? BookmarkPanel.NodeView)?.node ?: return
        val zones = availableZones(target)
        val currentIndex = zones.indexOf(dropZone).takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + delta + zones.size) % zones.size
        dropZone = zones[nextIndex]
        placement = BookmarkTreeDropSupport.DropPlacement(path, dropZone)
        updatePlacementLabel()
    }

    private fun availableZones(target: BookmarkNode): List<BookmarkTreeDropSupport.DropZone> {
        return when (target) {
            is BookmarkNode.Group, is BookmarkNode.Process -> listOf(
                BookmarkTreeDropSupport.DropZone.BEFORE,
                BookmarkTreeDropSupport.DropZone.INTO,
                BookmarkTreeDropSupport.DropZone.AFTER
            )
            else -> listOf(
                BookmarkTreeDropSupport.DropZone.BEFORE,
                BookmarkTreeDropSupport.DropZone.AFTER
            )
        }
    }

    private fun defaultZoneForTarget(target: BookmarkNode): BookmarkTreeDropSupport.DropZone {
        return when (target) {
            is BookmarkNode.Group, is BookmarkNode.Process -> BookmarkTreeDropSupport.DropZone.INTO
            else -> BookmarkTreeDropSupport.DropZone.AFTER
        }
    }

    private fun onTreeSelectionChanged() {
        val path = tree.selectionPath ?: return
        if (!BookmarkTreeUtil.isVerticalNavigationRow(tree, path)) return
        val target = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? BookmarkPanel.NodeView
            ?: return
        dropZone = defaultZoneForTarget(target.node)
        placement = BookmarkTreeDropSupport.DropPlacement(path, dropZone)
        updatePlacementLabel()
    }

    private fun commitPlacement(): Boolean {
        val current = placement ?: return false
        val targetNode = current.path.lastPathComponent as? DefaultMutableTreeNode ?: return false
        val target = (targetNode.userObject as? BookmarkPanel.NodeView)?.node ?: return false
        val (parentId, index) = BookmarkTreeDropSupport.resolveDropTarget(targetNode, target, current.zone)
        if (parentId == null || parentId == BookmarkStore.SUPER_ROOT_UUID) return false
        if (parentId == movingNode.uuid) return false
        val rootNode = displayRoot
        val moved = BookmarkDomainTree.findNode(rootNode, movingNode.uuid) ?: movingNode
        if (BookmarkDomainTree.isDescendant(moved, parentId)) return false
        onConfirm(parentId, index)
        return true
    }

    private fun installTreeKeys(popup: JBPopup, onEnter: () -> Unit) {
        tree.addTreeSelectionListener { onTreeSelectionChanged() }
        val inputMap = tree.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = tree.actionMap
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "moveUp")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "moveDown")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "zonePrev")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "zoneNext")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "confirm")
        actionMap.put("moveUp", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, -1)
                onTreeSelectionChanged()
            }
        })
        actionMap.put("moveDown", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, 1)
                onTreeSelectionChanged()
            }
        })
        actionMap.put("zonePrev", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                cycleDropZone(-1)
            }
        })
        actionMap.put("zoneNext", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                cycleDropZone(1)
            }
        })
        actionMap.put("confirm", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (commitPlacement()) {
                    onEnter()
                    popup.closeOk(null)
                }
            }
        })
    }
}
