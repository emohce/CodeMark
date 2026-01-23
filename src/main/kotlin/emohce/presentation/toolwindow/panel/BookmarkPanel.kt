package emohce.presentation.toolwindow.panel

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.psi.PsiManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.FormBuilder
import emohce.domain.model.BookmarkNode
import emohce.presentation.selection.SelectionBus
import emohce.presentation.toolwindow.BookmarkIntent
import emohce.presentation.toolwindow.BookmarkSideEffect
import emohce.presentation.toolwindow.BookmarkViewModel
import emohce.presentation.toolwindow.BookmarkViewState
import emohce.presentation.toolwindow.panel.render.BookmarkTreeCellRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.DropMode
import javax.swing.InputMap
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.JToggleButton
import javax.swing.TransferHandler
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class BookmarkPanel(
    private val project: Project,
    private val viewModel: BookmarkViewModel
) : JPanel(BorderLayout()), Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Loading"))
    private val tree = Tree(treeModel)
    private val searchField = JTextField(24)
    private var currentRoot: BookmarkNode.Group? = null
    private var currentReferenceCounts: Map<String, Int> = emptyMap()
    private var currentReferenceTargets: Set<String> = emptySet()
    private var currentTargetsBySource: Map<String, List<String>> = emptyMap()
    private var currentSourcesByTarget: Map<String, List<String>> = emptyMap()
    private var currentPathMap: Map<String, String> = emptyMap()
    private var currentState: BookmarkViewState = BookmarkViewState()
    private var lastSelectedBeforeSearch: String? = null
    private var pendingSelectionAfterClear: String? = null

    private fun showPanelOkCancel(panel: JComponent, title: String): Boolean {
        val dialog = object : DialogWrapper(project) {
            init {
                this.title = title
                init()
            }

            override fun createCenterPanel(): JComponent = panel
        }
        return dialog.showAndGet()
    }

    init {
        tree.isRootVisible = true
        tree.cellRenderer = BookmarkTreeCellRenderer()
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.addTreeSelectionListener {
            val node = selectedNode()
            if (node != null) {
                viewModel.processIntent(BookmarkIntent.SelectNode(node.uuid))
                if (node is BookmarkNode.Bookmark) {
                    viewModel.processIntent(BookmarkIntent.NavigateToBookmark(node))
                } else if (node is BookmarkNode.Process) {
                    val entryPath = node.entryFilePath
                    val entryLine = node.entryLine
                    if (!entryPath.isNullOrBlank() && entryLine != null) {
                        navigateToFile(entryPath, entryLine, 0)
                    }
                }
            }
            SelectionBus.getInstance(project).setCurrentContainerId(currentContainerId())
            SelectionBus.getInstance(project).setLastSelectedNodeId(node?.uuid)
        }
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "collapse")
        tree.actionMap.put("collapse", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                tree.collapsePath(tree.selectionPath)
            }
        })
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "expand")
        tree.actionMap.put("expand", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                tree.expandPath(tree.selectionPath)
            }
        })
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) {
                val node = (event.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? NodeView
                if (node != null) {
                    populateChildren(event.path.lastPathComponent as DefaultMutableTreeNode, node.node)
                    viewModel.processIntent(BookmarkIntent.ExpandNode(node.node.uuid))
                }
            }

            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) {
                val node = (event.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? NodeView
                node?.let { viewModel.processIntent(BookmarkIntent.CollapseNode(it.node.uuid)) }
            }
        })

        tree.dragEnabled = true
        tree.dropMode = DropMode.ON_OR_INSERT
        tree.transferHandler = NodeTransferHandler()

        tree.componentPopupMenu = createPopupMenu()
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) selectNodeAt(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) selectNodeAt(e)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                    navigateSelectedBookmark()
                }
            }
        })

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        val addGroup = JButton("Add Group")
        val addProcess = JButton("Add Process")
        val addBookmark = JButton("Add Bookmark")
        val addNote = JButton("Add Note")
        val addStep = JButton("Add Step")
        val editNode = JButton("Edit")
        val moveNode = JButton("Move")
        val deleteNode = JButton("Delete")
        val setEntry = JButton("Set Entry")
        val refresh = JButton("Refresh")
        val prev = JButton("Prev")
        val next = JButton("Next")
        val search = JButton("Search")
        val clearSearch = JButton("Clear")
        val showRefs = JButton("Refs")
        val showGraph = JButton("Graph")
        val openKeymap = JButton("Keymap")
        val filterBookmarks = JToggleButton("B", true)
        val filterProcesses = JToggleButton("P", true)
        val filterNotes = JToggleButton("N", true)
        val filterGroups = JToggleButton("G", false)

        addGroup.addActionListener { createGroup() }
        addProcess.addActionListener { createProcess() }
        addBookmark.addActionListener { createBookmark() }
        addNote.addActionListener { createDescriptive() }
        addStep.addActionListener { addToProcessStep() }
        editNode.addActionListener { editSelected() }
        moveNode.addActionListener { moveSelected() }
        deleteNode.addActionListener { deleteSelected() }
        setEntry.addActionListener { setProcessEntry() }
        refresh.addActionListener { viewModel.processIntent(BookmarkIntent.Refresh) }
        prev.addActionListener { viewModel.processIntent(BookmarkIntent.NavigateToPrevInProcess) }
        next.addActionListener { viewModel.processIntent(BookmarkIntent.NavigateToNextInProcess) }
        search.addActionListener { startSearch() }
        searchField.addActionListener { startSearch() }
        clearSearch.addActionListener { clearSearchAndRestore() }
        showRefs.addActionListener { showReferenceOverview() }
        showGraph.addActionListener { showReferenceGraph() }
        openKeymap.addActionListener { openKeymapSettings() }
        filterBookmarks.addActionListener { startSearch() }
        filterProcesses.addActionListener { startSearch() }
        filterNotes.addActionListener { startSearch() }
        filterGroups.addActionListener { startSearch() }

        toolbar.add(addGroup)
        toolbar.add(addProcess)
        toolbar.add(addBookmark)
        toolbar.add(addNote)
        toolbar.add(addStep)
        toolbar.add(editNode)
        toolbar.add(moveNode)
        toolbar.add(deleteNode)
        toolbar.add(setEntry)
        toolbar.add(refresh)
        toolbar.add(prev)
        toolbar.add(next)
        toolbar.add(JLabel("Query:"))
        toolbar.add(searchField)
        toolbar.add(search)
        toolbar.add(clearSearch)
        toolbar.add(showRefs)
        toolbar.add(showGraph)
        toolbar.add(openKeymap)
        toolbar.add(filterBookmarks)
        toolbar.add(filterProcesses)
        toolbar.add(filterNotes)
        toolbar.add(filterGroups)

        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)

        installShortcuts()
        checkShortcutConflicts()

        viewModel.state.onEach { state ->
            updateTree(state)
        }.launchIn(scope)

        viewModel.sideEffects.onEach { effect ->
            handleSideEffect(effect)
        }.launchIn(scope)

        SelectionBus.getInstance(project).requests.onEach { nodeId ->
            viewModel.processIntent(BookmarkIntent.Refresh)
            selectNodeById(nodeId)
        }.launchIn(scope)
    }

    private fun updateTree(state: BookmarkViewState) {
        currentState = state
        currentRoot = state.rootNode
        currentReferenceCounts = state.referenceCounts
        currentReferenceTargets = state.referenceTargets
        currentTargetsBySource = state.referenceTargetsBySource
        currentSourcesByTarget = state.referenceSourcesByTarget
        currentPathMap = state.rootNode?.let { buildPathMap(it) } ?: emptyMap()
        val rootNode = state.rootNode
        val newRootNode = when {
            state.searchQuery.isNotBlank() -> buildSearchTree(state)
            rootNode != null -> buildTreeNodeLazy(
                rootNode,
                state.referenceCounts,
                state.referenceTargets,
                false,
                "Root",
                state.expandedNodeIds
            )
            else -> DefaultMutableTreeNode("No data")
        }
        val currentRootNode = treeModel.root as? DefaultMutableTreeNode
        if (currentRootNode == null) {
            treeModel.setRoot(newRootNode)
        } else {
            val updated = applyDiff(treeModel, currentRootNode, newRootNode)
            if (!updated) {
                // if diff failed, fallback to replace
                treeModel.setRoot(newRootNode)
            }
        }
        restoreExpandedNodes(state.expandedNodeIds)
        pendingSelectionAfterClear?.let { pending ->
            pendingSelectionAfterClear = null
            selectNodeById(pending)
        }
    }

    private fun buildSearchTree(state: BookmarkViewState): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode("Search Results")
        val sourceRoot = currentRoot ?: return root
        val matchIds = state.searchResults.map { it.uuid }.toSet()
        val filtered = buildFilteredTree(sourceRoot, matchIds, "Root")
        if (filtered != null) {
            return filtered
        }
        return root
    }

    private fun buildTreeNodeLazy(
        node: BookmarkNode,
        referenceCounts: Map<String, Int>,
        referenceTargets: Set<String>,
        isSearchResult: Boolean = false,
        pathLabel: String = "Root",
        expandedIds: Set<String> = emptySet()
    ): DefaultMutableTreeNode {
        val treeNode = DefaultMutableTreeNode(
            NodeView(
                node,
                referenceCounts[node.uuid] ?: 0,
                referenceTargets.contains(node.uuid),
                isSearchResult,
                pathLabel
            )
        )
        val children = when (node) {
            is BookmarkNode.Group -> node.children
            is BookmarkNode.Process -> node.steps
            else -> emptyList()
        }
        if (children.isNotEmpty()) {
            val shouldExpand = node.uuid == "root" || expandedIds.contains(node.uuid)
            if (shouldExpand) {
                children.forEach { child ->
                    val childPath = "$pathLabel/${child.name.ifBlank { "(unnamed)" }}"
                    treeNode.add(
                        buildTreeNodeLazy(
                            child,
                            referenceCounts,
                            referenceTargets,
                            isSearchResult,
                            childPath,
                            expandedIds
                        )
                    )
                }
            } else {
                treeNode.add(createPlaceholderNode())
            }
        }
        return treeNode
    }

    private fun buildFilteredTree(
        node: BookmarkNode,
        matchIds: Set<String>,
        pathLabel: String
    ): DefaultMutableTreeNode? {
        val isMatch = matchIds.contains(node.uuid)
        val children = when (node) {
            is BookmarkNode.Group -> node.children
            is BookmarkNode.Process -> node.steps
            else -> emptyList()
        }
        val childNodes = children.mapNotNull { child ->
            val childPath = "$pathLabel/${child.name.ifBlank { "(unnamed)" }}"
            buildFilteredTree(child, matchIds, childPath)
        }
        if (!isMatch && childNodes.isEmpty()) return null
        val treeNode = DefaultMutableTreeNode(
            NodeView(
                node,
                currentReferenceCounts[node.uuid] ?: 0,
                currentReferenceTargets.contains(node.uuid),
                isMatch,
                pathLabel
            )
        )
        childNodes.forEach { treeNode.add(it) }
        return treeNode
    }

    private fun selectedNode(): BookmarkNode? {
        val selected = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return (selected.userObject as? NodeView)?.node
    }

    private fun selectedNodes(): List<BookmarkNode> {
        val paths = tree.selectionPaths ?: return emptyList()
        return paths.mapNotNull { path ->
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return@mapNotNull null
            (node.userObject as? NodeView)?.node
        }.filter { it.uuid != "root" }
    }

    private fun insertionTarget(): Pair<String?, Int?> {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return "root" to null
        val lastSelectedId = SelectionBus.getInstance(project).getLastSelectedNodeId()
        val selectedTreeNode = lastSelectedId?.let { findNodeById(root, it) }
        val selectedView = selectedTreeNode?.userObject as? NodeView
        val selectedNode = selectedView?.node

        return when (selectedNode) {
            is BookmarkNode.Group, is BookmarkNode.Process -> {
                val childCount = realChildCount(selectedTreeNode)
                selectedNode.uuid to childCount
            }
            is BookmarkNode.Bookmark, is BookmarkNode.DescriptiveBookmark -> {
                val parent = selectedTreeNode.parent as? DefaultMutableTreeNode
                val parentView = parent?.userObject as? NodeView
                val parentId = when (val pNode = parentView?.node) {
                    is BookmarkNode.Group -> pNode.uuid
                    is BookmarkNode.Process -> pNode.uuid
                    else -> "root"
                }
                val indexInParent = parent?.getIndex(selectedTreeNode)?.takeIf { it >= 0 }
                parentId to indexInParent?.plus(1)
            }
            else -> "root" to null
        }
    }

    private fun realChildCount(node: DefaultMutableTreeNode?): Int {
        if (node == null) return 0
        if (hasPlaceholder(node)) return 0
        return node.childCount
    }

    private fun currentContainerId(): String? {
        val selected = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        val node = (selected.userObject as? NodeView)?.node
        val containerId = when (node) {
            is BookmarkNode.Group -> node.uuid
            is BookmarkNode.Process -> node.uuid
            else -> {
                val parent = selected.parent as? DefaultMutableTreeNode ?: return null
                val parentNode = (parent.userObject as? NodeView)?.node
                when (parentNode) {
                    is BookmarkNode.Group -> parentNode.uuid
                    is BookmarkNode.Process -> parentNode.uuid
                    else -> null
                }
            }
        }
        if (node != null && currentState.searchQuery.isNotBlank() && pendingSelectionAfterClear == null) {
            pendingSelectionAfterClear = node.uuid
            viewModel.processIntent(BookmarkIntent.ClearSearch)
        }
        return containerId
    }

    private fun selectNodeAt(event: MouseEvent) {
        val row = tree.getClosestRowForLocation(event.x, event.y)
        if (row >= 0) tree.setSelectionRow(row)
    }

    private fun startSearch() {
        val query = searchField.text.trim()
        if (query.isNotBlank() && currentState.searchQuery.isBlank()) {
            lastSelectedBeforeSearch = selectedNode()?.uuid
        }
        viewModel.processIntent(BookmarkIntent.Search(query, activeFilters()))
    }

    private fun activeFilters(): Set<emohce.presentation.toolwindow.SearchFilter> {
        val filters = mutableSetOf<emohce.presentation.toolwindow.SearchFilter>()
        val toolbar = (components.firstOrNull() as? JPanel) ?: return filters
        toolbar.components.forEach { component ->
            if (component is JToggleButton) {
                when (component.text) {
                    "B" -> if (component.isSelected) filters.add(emohce.presentation.toolwindow.SearchFilter.BOOKMARK)
                    "P" -> if (component.isSelected) filters.add(emohce.presentation.toolwindow.SearchFilter.PROCESS)
                    "N" -> if (component.isSelected) filters.add(emohce.presentation.toolwindow.SearchFilter.NOTE)
                    "G" -> if (component.isSelected) filters.add(emohce.presentation.toolwindow.SearchFilter.GROUP)
                }
            }
        }
        if (filters.isEmpty()) {
            filters.add(emohce.presentation.toolwindow.SearchFilter.BOOKMARK)
            filters.add(emohce.presentation.toolwindow.SearchFilter.PROCESS)
            filters.add(emohce.presentation.toolwindow.SearchFilter.NOTE)
        }
        return filters
    }

    private fun clearSearchAndRestore() {
        val restoreId = lastSelectedBeforeSearch
        pendingSelectionAfterClear = restoreId
        lastSelectedBeforeSearch = null
        viewModel.processIntent(BookmarkIntent.ClearSearch)
    }

    private fun clearSearchAndReturn() {
        clearSearchAndRestore()
        tree.requestFocusInWindow()
    }

    private fun selectNodeById(nodeId: String) {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return
        val node = findNodeById(root, nodeId) ?: return
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.expandPath(path)
        tree.scrollPathToVisible(path)
    }

    private fun restoreExpandedNodes(expandedIds: Set<String>) {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return
        expandedIds.forEach { id ->
            val node = findNodeById(root, id) ?: return@forEach
            val view = node.userObject as? NodeView
            if (view != null) {
                populateChildren(node, view.node)
            }
            val path = TreePath(node.path)
            tree.expandPath(path)
        }
    }

    private fun findNodeById(root: DefaultMutableTreeNode, nodeId: String): DefaultMutableTreeNode? {
        val view = root.userObject as? NodeView
        if (view?.node?.uuid == nodeId) return root
        val children = root.children()
        while (children.hasMoreElements()) {
            val child = children.nextElement() as DefaultMutableTreeNode
            val match = findNodeById(child, nodeId)
            if (match != null) return match
        }
        return null
    }

    private fun populateChildren(treeNode: DefaultMutableTreeNode, node: BookmarkNode) {
        if (!hasPlaceholder(treeNode)) return
        treeNode.removeAllChildren()
        val children = when (node) {
            is BookmarkNode.Group -> node.children
            is BookmarkNode.Process -> node.steps
            else -> emptyList()
        }
        children.forEach { child ->
            val pathLabel = buildPathLabel(child)
            treeNode.add(
                buildTreeNodeLazy(
                    child,
                    currentReferenceCounts,
                    currentReferenceTargets,
                    false,
                    pathLabel,
                    currentState.expandedNodeIds
                )
            )
        }
        treeModel.nodeStructureChanged(treeNode)
    }

    private fun hasPlaceholder(node: DefaultMutableTreeNode): Boolean {
        if (node.childCount != 1) return false
        val child = node.getChildAt(0) as? DefaultMutableTreeNode ?: return false
        return child.userObject == PLACEHOLDER_LABEL
    }

    private fun createPlaceholderNode(): DefaultMutableTreeNode {
        return DefaultMutableTreeNode(PLACEHOLDER_LABEL)
    }

    private fun buildPathLabel(node: BookmarkNode): String {
        return currentPathMap[node.uuid] ?: "Root/${node.name.ifBlank { "(unnamed)" }}"
    }

    private fun applyDiff(
        model: DefaultTreeModel,
        current: DefaultMutableTreeNode,
        updated: DefaultMutableTreeNode
    ): Boolean {
        if (nodeKey(current) != nodeKey(updated)) return false

        val existingChildren = nodeChildren(current)
        val existingMap = existingChildren.associateBy { nodeKey(it) }.toMutableMap()
        val desiredKeys = mutableListOf<String>()

        val updatedChildren = nodeChildren(updated)
        updatedChildren.forEachIndexed { index, updatedChild ->
            val key = nodeKey(updatedChild)
            desiredKeys.add(key)
            val existing = existingMap.remove(key)
            if (existing == null) {
                val copy = copyNode(updatedChild)
                model.insertNodeInto(copy, current, index.coerceAtMost(current.childCount))
            } else {
                applyDiff(model, existing, updatedChild)
                val currentIndex = current.getIndex(existing)
                if (currentIndex != index) {
                    model.removeNodeFromParent(existing)
                    model.insertNodeInto(existing, current, index.coerceAtMost(current.childCount))
                }
            }
        }
        // remove extras
        existingMap.values.forEach { toRemove ->
            model.removeNodeFromParent(toRemove)
        }
        model.nodeStructureChanged(current)
        return true
    }

    private fun nodeKey(node: DefaultMutableTreeNode): String {
        val obj = node.userObject
        val view = obj as? NodeView
        return view?.node?.uuid ?: obj?.toString().orEmpty()
    }

    private fun copyNode(source: DefaultMutableTreeNode): DefaultMutableTreeNode {
        val copy = DefaultMutableTreeNode(source.userObject)
        val children = nodeChildren(source)
        children.forEach { child ->
            copy.add(copyNode(child))
        }
        return copy
    }

    private fun nodeChildren(node: DefaultMutableTreeNode): List<DefaultMutableTreeNode> {
        val result = mutableListOf<DefaultMutableTreeNode>()
        val children = node.children()
        while (children.hasMoreElements()) {
            val child = children.nextElement()
            if (child is DefaultMutableTreeNode) {
                result.add(child)
            }
        }
        return result
    }

    private fun moveTreeNode(nodeId: String, parentId: String, index: Int) {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return
        val node = findNodeById(root, nodeId) ?: return
        val parent = findNodeById(root, parentId) ?: return

        val currentParent = node.parent as? DefaultMutableTreeNode
        if (currentParent != null) {
            treeModel.removeNodeFromParent(node)
        }

        val targetIndex = if (index < 0) parent.childCount else index.coerceAtMost(parent.childCount)
        treeModel.insertNodeInto(node, parent, targetIndex)
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.expandPath(path)
        tree.scrollPathToVisible(path)
    }

    private fun createGroup() {
        val name = Messages.showInputDialog(project, "Group name:", "Create Group", null) ?: return
        if (name.isBlank()) return
        val (parentId, insertIndex) = insertionTarget()
        val group = BookmarkNode.Group(name = name.trim())
        viewModel.processIntent(BookmarkIntent.CreateGroup(parentId, group, insertIndex))
        SelectionBus.getInstance(project).requestSelect(group.uuid)
    }

    private fun createProcess() {
        val name = Messages.showInputDialog(project, "Process name:", "Create Process", null) ?: return
        if (name.isBlank()) return
        val description = Messages.showInputDialog(project, "Description:", "Create Process", null) ?: ""
        val entryPath = Messages.showInputDialog(project, "Entry file path (optional):", "Create Process", null)
        val entryLineText = Messages.showInputDialog(project, "Entry line (optional):", "Create Process", null)
        val entryLine = entryLineText?.toIntOrNull()
        if (!entryPath.isNullOrBlank() && !ensureFileExists(entryPath, "Create Process")) return
        val (parentId, insertIndex) = insertionTarget()
        val process = BookmarkNode.Process(
            name = name.trim(),
            description = description,
            entryFilePath = entryPath?.takeIf { it.isNotBlank() },
            entryLine = entryLine
        )
        viewModel.processIntent(BookmarkIntent.CreateProcess(parentId, process, insertIndex))
        SelectionBus.getInstance(project).requestSelect(process.uuid)
    }

    private fun createBookmark() {
        val name = Messages.showInputDialog(project, "Bookmark name:", "Create Bookmark", null) ?: return
        if (name.isBlank()) return
        val filePath = Messages.showInputDialog(project, "File path:", "Create Bookmark", null) ?: return
        if (!ensureFileExists(filePath, "Create Bookmark")) return
        val lineText = Messages.showInputDialog(project, "Line number:", "Create Bookmark", null) ?: "0"
        val line = (lineText.toIntOrNull() ?: 0).coerceAtLeast(0)
        val (parentId, insertIndex) = insertionTarget()
        val bookmark = BookmarkNode.Bookmark(name = name.trim(), filePath = filePath.trim(), line = line)
        viewModel.processIntent(BookmarkIntent.CreateBookmark(parentId, bookmark, insertIndex))
        SelectionBus.getInstance(project).requestSelect(bookmark.uuid)
    }

    private fun createDescriptive() {
        val name = Messages.showInputDialog(project, "Note title:", "Create Note", null) ?: return
        if (name.isBlank()) return
        val description = Messages.showInputDialog(project, "Description:", "Create Note", null) ?: ""
        val markdown = Messages.showInputDialog(project, "Markdown:", "Create Note", null) ?: ""
        val (parentId, insertIndex) = insertionTarget()
        val note = BookmarkNode.DescriptiveBookmark(
            name = name.trim(),
            description = description.trim(),
            markdownContent = markdown
        )
        viewModel.processIntent(BookmarkIntent.CreateDescriptive(parentId, note, insertIndex))
        SelectionBus.getInstance(project).requestSelect(note.uuid)
    }

    private fun editSelected() {
        val node = selectedNode() ?: return
        if (node.uuid == "root") return
        val updated = when (node) {
            is BookmarkNode.Bookmark -> editBookmark(node)
            is BookmarkNode.DescriptiveBookmark -> editDescriptive(node)
            is BookmarkNode.Group -> editGroup(node)
            is BookmarkNode.Process -> editProcess(node)
        } ?: return
        viewModel.processIntent(BookmarkIntent.EditNode(updated))
    }

    private fun editBookmark(node: BookmarkNode.Bookmark): BookmarkNode.Bookmark? {
        val nameField = JTextField(node.name)
        val descField = JTextField(node.description)
        val pathField = JTextField(node.filePath)
        val lineField = JTextField(node.line.toString())
        val columnField = JTextField(node.column.toString())
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name", nameField)
            .addLabeledComponent("Description", descField)
            .addLabeledComponent("File path", pathField)
            .addLabeledComponent("Line", lineField)
            .addLabeledComponent("Column", columnField)
            .panel

        if (!showPanelOkCancel(panel, "Edit Bookmark")) return null
        val path = pathField.text.trim()
        if (!ensureFileExists(path, "Edit Bookmark")) return null
        val line = (lineField.text.trim().toIntOrNull() ?: node.line).coerceAtLeast(0)
        val column = (columnField.text.trim().toIntOrNull() ?: node.column).coerceAtLeast(0)
        return node.copy(
            name = nameField.text.trim(),
            description = descField.text.trim(),
            filePath = path,
            line = line,
            column = column
        )
    }

    private fun editDescriptive(node: BookmarkNode.DescriptiveBookmark): BookmarkNode.DescriptiveBookmark? {
        val nameField = JTextField(node.name)
        val descField = JTextField(node.description)
        val markdownField = JTextArea(node.markdownContent, 6, 40)
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name", nameField)
            .addLabeledComponent("Description", descField)
            .addLabeledComponent("Markdown", JScrollPane(markdownField))
            .panel

        if (!showPanelOkCancel(panel, "Edit Description")) return null
        return node.copy(
            name = nameField.text.trim(),
            description = descField.text.trim(),
            markdownContent = markdownField.text
        )
    }

    private fun editGroup(node: BookmarkNode.Group): BookmarkNode.Group? {
        val nameField = JTextField(node.name)
        val descField = JTextField(node.description)
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name", nameField)
            .addLabeledComponent("Description", descField)
            .panel

        if (!showPanelOkCancel(panel, "Edit Group")) return null
        return node.copy(name = nameField.text.trim(), description = descField.text.trim())
    }

    private fun editProcess(node: BookmarkNode.Process): BookmarkNode.Process? {
        val nameField = JTextField(node.name)
        val descField = JTextField(node.description)
        val entryPathField = JTextField(node.entryFilePath ?: "")
        val entryLineField = JTextField(node.entryLine?.toString() ?: "")
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name", nameField)
            .addLabeledComponent("Description", descField)
            .addLabeledComponent("Entry file path", entryPathField)
            .addLabeledComponent("Entry line", entryLineField)
            .panel

        if (!showPanelOkCancel(panel, "Edit Process")) return null
        val entryPath = entryPathField.text.trim().ifBlank { null }
        if (!entryPath.isNullOrBlank() && !ensureFileExists(entryPath, "Edit Process")) return null
        val entryLine = entryLineField.text.trim().toIntOrNull()
        return node.copy(
            name = nameField.text.trim(),
            description = descField.text.trim(),
            entryFilePath = entryPath,
            entryLine = entryLine
        )
    }

    private fun setProcessEntry() {
        val node = selectedNode() as? BookmarkNode.Process ?: return
        val entryPath = Messages.showInputDialog(
            project,
            "Entry file path:",
            "Set Process Entry",
            null,
            node.entryFilePath ?: "",
            null
        ) ?: return
        if (entryPath.isNotBlank() && !ensureFileExists(entryPath, "Set Process Entry")) return
        val entryLineText = Messages.showInputDialog(
            project,
            "Entry line:",
            "Set Process Entry",
            null,
            node.entryLine?.toString() ?: "",
            null
        ) ?: ""
        val entryLine = entryLineText.trim().toIntOrNull()
        val updated = node.copy(
            entryFilePath = entryPath.trim().ifBlank { null },
            entryLine = entryLine
        )
        viewModel.processIntent(BookmarkIntent.EditNode(updated))
    }

    private fun moveSelected() {
        val node = selectedNode() ?: return
        val root = currentRoot ?: return
        val containers = collectContainers(root, "Root")
            .filter { it.id != node.uuid }
            .filterNot { isDescendant(node, it.id) }
        if (containers.isEmpty()) return

        val labels = containers.map { it.label }.toTypedArray()
        val choiceIndex = chooseIndex("Move to:", "Move", labels)
        val target = containers.getOrNull(choiceIndex) ?: return
        viewModel.processIntent(BookmarkIntent.MoveNode(node.uuid, target.id, -1))
    }

    private fun createReference() {
        val source = selectedNode() as? BookmarkNode.Bookmark ?: return
        val root = currentRoot ?: return
        val bookmarks = collectBookmarks(root).filter { it.uuid != source.uuid }
        if (bookmarks.isEmpty()) return
        val labels = bookmarks.map { formatBookmarkLabel(it) }.toTypedArray()
        val choiceIndex = chooseIndex("Select target bookmark:", "Create Reference", labels)
        val target = bookmarks.getOrNull(choiceIndex) ?: return
        viewModel.processIntent(BookmarkIntent.CreateReference(source.uuid, target.uuid))
    }

    private fun showReferenceOverview() {
        val root = currentRoot ?: return
        val all = collectBookmarks(root).associateBy { it.uuid }
        val pathMap = buildPathMap(root)
        val entries = mutableListOf<ReferenceEntry>()

        currentTargetsBySource.forEach { (sourceId, targets) ->
            targets.forEach { targetId ->
                val source = all[sourceId]
                val target = all[targetId]
                if (source != null && target != null) {
                    val sourcePath = pathMap[source.uuid] ?: source.name
                    val targetPath = pathMap[target.uuid] ?: target.name
                    entries.add(ReferenceEntry(source, target, sourcePath, targetPath))
                }
            }
        }
        if (entries.isEmpty()) {
            Messages.showMessageDialog(project, "No references", "References", null)
            return
        }
        val list = JBList(entries.map { it.label }.toTypedArray())
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        val dialog = object : DialogWrapper(project) {
            init {
                title = "References"
                init()
            }

            override fun createCenterPanel(): JComponent {
                return JBScrollPane(list)
            }
        }
        if (!dialog.showAndGet()) return
        val selected = list.selectedIndices.toList().mapNotNull { index -> entries.getOrNull(index) }
        if (selected.isEmpty()) return
        selected.forEach { entry ->
            selectNodeById(entry.target.uuid)
            selectNodeById(entry.source.uuid)
        }
    }

    private fun showReferenceGraph() {
        val root = currentRoot ?: return
        if (currentTargetsBySource.isEmpty()) {
            Messages.showMessageDialog(project, "No references", "Reference Graph", null)
            return
        }
        val all = collectBookmarks(root).associateBy { it.uuid }
        val pathMap = buildPathMap(root)
        val selected = selectedNode() as? BookmarkNode.Bookmark
        val selectedId = selected?.uuid
        val filterBox = javax.swing.JCheckBox("Only selected node chain", selectedId != null)
        val focusButton = JButton("Focus Selected")
        val showAllButton = JButton("Show All")

        fun buildVisualData(): Pair<List<VisualNode>, List<VisualEdge>> {
            val edges = mutableListOf<VisualEdge>()
            currentTargetsBySource.forEach { (sourceId, targets) ->
                targets.forEach { targetId ->
                    if (filterBox.isSelected && selectedId != null) {
                        if (sourceId != selectedId && targetId != selectedId) return@forEach
                    }
                    edges.add(VisualEdge(sourceId, targetId))
                }
            }
            val nodeIds = edges.flatMap { listOf(it.fromId, it.toId) }.toSet()
            val nodes = nodeIds.mapNotNull { id ->
                val node = all[id] ?: return@mapNotNull null
                VisualNode(id, formatGraphLabel(node, pathMap))
            }
            return nodes to edges
        }

        val graphPanel = ReferenceGraphPanel { nodeId ->
            selectNodeById(nodeId)
        }

        fun refreshGraph() {
            val (nodes, edges) = buildVisualData()
            graphPanel.updateData(nodes, edges, selectedId)
        }

        refreshGraph()
        filterBox.addActionListener { refreshGraph() }
        focusButton.addActionListener {
            if (selectedId != null) {
                filterBox.isSelected = true
                refreshGraph()
            }
        }
        showAllButton.addActionListener {
            filterBox.isSelected = false
            refreshGraph()
        }

        val dialog = object : DialogWrapper(project) {
            init {
                title = "Reference Graph"
                init()
            }

            override fun createCenterPanel(): JComponent {
                val controls = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(filterBox)
                    add(focusButton)
                    add(showAllButton)
                }
                return FormBuilder.createFormBuilder()
                    .addComponent(controls)
                    .addComponent(graphPanel)
                    .panel
            }
        }
        dialog.show()
    }

    private data class ReferenceEntry(
        val source: BookmarkNode.Bookmark,
        val target: BookmarkNode.Bookmark,
        val sourcePath: String,
        val targetPath: String
    ) {
        val label: String = "$sourcePath -> $targetPath"
    }

    private data class VisualNode(
        val id: String,
        val label: String,
        var x: Int = 0,
        var y: Int = 0
    )

    private data class VisualEdge(val fromId: String, val toId: String)

    private class ReferenceGraphPanel(
        private val onNodeDoubleClick: (String) -> Unit
    ) : JPanel() {
        private var nodes: List<VisualNode> = emptyList()
        private var edges: List<VisualEdge> = emptyList()
        private var selectedId: String? = null

        init {
            preferredSize = Dimension(640, 480)
            background = Color(0xF8F8F8)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2) return
                    val hit = nodes.minByOrNull { node ->
                        val dx = e.x - node.x
                        val dy = e.y - node.y
                        dx * dx + dy * dy
                    }
                    if (hit != null && distance(hit.x, hit.y, e.x, e.y) <= NODE_RADIUS * NODE_RADIUS) {
                        onNodeDoubleClick(hit.id)
                    }
                }
            })
            toolTipText = ""
            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val hit = hitNode(e.x, e.y)
                    toolTipText = hit?.label
                }
            })
        }

        fun updateData(nodes: List<VisualNode>, edges: List<VisualEdge>, selectedId: String?) {
            this.nodes = nodes
            this.edges = edges
            this.selectedId = selectedId
            layoutNodes()
            repaint()
        }

        private fun layoutNodes() {
            if (nodes.isEmpty()) return
            val canvasWidth = width.takeIf { it > 0 } ?: preferredSize.width
            val canvasHeight = height.takeIf { it > 0 } ?: preferredSize.height
            val centerX = canvasWidth / 2
            val centerY = canvasHeight / 2
            val radius = (minOf(canvasWidth, canvasHeight) / 2.0 * 0.78).toInt().coerceAtLeast(90)
            val angleStep = 2 * Math.PI / nodes.size
            nodes.forEachIndexed { index, node ->
                val angle = index * angleStep
                node.x = (centerX + radius * Math.cos(angle)).toInt()
                node.y = (centerY + radius * Math.sin(angle)).toInt()
            }
        }

        override fun paintComponent(g: java.awt.Graphics) {
            super.paintComponent(g)
            val g2d = g as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            edges.forEach { edge ->
                val from = nodes.find { it.id == edge.fromId }
                val to = nodes.find { it.id == edge.toId }
                if (from != null && to != null) {
                    drawArrow(g2d, from, to)
                }
            }
            nodes.forEach { node ->
                val isSelected = node.id == selectedId
                g2d.color = if (isSelected) Color(0x1976D2) else Color(0x2196F3)
                g2d.fillOval(node.x - NODE_RADIUS, node.y - NODE_RADIUS, NODE_DIAMETER, NODE_DIAMETER)
                g2d.color = Color.WHITE
                val text = node.label
                val metrics = g2d.fontMetrics
                val textWidth = metrics.stringWidth(text)
                val textX = node.x - textWidth / 2
                val textY = node.y + metrics.ascent / 2
                g2d.drawString(text, textX, textY)
            }
        }

        private fun drawArrow(g2d: Graphics2D, from: VisualNode, to: VisualNode) {
            val dx = (to.x - from.x).toDouble()
            val dy = (to.y - from.y).toDouble()
            val dist = Math.hypot(dx, dy).coerceAtLeast(1.0)
            val ux = dx / dist
            val uy = dy / dist
            val startX = from.x + (NODE_RADIUS * ux).toInt()
            val startY = from.y + (NODE_RADIUS * uy).toInt()
            val endX = to.x - (NODE_RADIUS * ux).toInt()
            val endY = to.y - (NODE_RADIUS * uy).toInt()
            g2d.color = Color(0x555555)
            g2d.stroke = BasicStroke(1.6f)
            val line = Line2D.Double(startX.toDouble(), startY.toDouble(), endX.toDouble(), endY.toDouble())
            g2d.draw(line)

            val arrowSize = 10.0
            val angle = Math.atan2(dy, dx)
            val arrowPath = Path2D.Double().apply {
                moveTo(0.0, 0.0)
                lineTo(-arrowSize, arrowSize / 1.6)
                lineTo(-arrowSize, -arrowSize / 1.6)
                closePath()
            }
            val transform = AffineTransform()
            transform.translate(endX.toDouble(), endY.toDouble())
            transform.rotate(angle)
            val arrowShape = transform.createTransformedShape(arrowPath)
            g2d.fill(arrowShape)
        }

        private fun hitNode(x: Int, y: Int): VisualNode? {
            return nodes.minByOrNull { node ->
                val dx = x - node.x
                val dy = y - node.y
                dx * dx + dy * dy
            }?.takeIf { distance(it.x, it.y, x, y) <= NODE_RADIUS * NODE_RADIUS }
        }

        private fun distance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
            val dx = x1 - x2
            val dy = y1 - y2
            return dx * dx + dy * dy
        }

        companion object {
            private const val NODE_RADIUS = 22
            private const val NODE_DIAMETER = NODE_RADIUS * 2
        }
    }

    private fun copyToProcess() {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        val root = currentRoot ?: return
        val blockedTargets = nodes.filterIsInstance<BookmarkNode.Process>().map { it.uuid }.toSet()
        val processes = collectProcesses(root).filter { it.uuid !in blockedTargets }
        if (processes.isEmpty()) return
        val labels = processes.map { it.name.ifBlank { "(unnamed)" } }.toTypedArray()
        val choiceIndex = chooseIndex("Select process:", "Copy To Process", labels)
        val target = processes.getOrNull(choiceIndex) ?: return
        nodes.forEach { node ->
            val clone = cloneForInsert(node)
            when (clone) {
                is BookmarkNode.Bookmark ->
                    viewModel.processIntent(BookmarkIntent.CreateBookmark(target.uuid, clone, null))
                is BookmarkNode.DescriptiveBookmark ->
                    viewModel.processIntent(BookmarkIntent.CreateDescriptive(target.uuid, clone, null))
                is BookmarkNode.Group ->
                    viewModel.processIntent(BookmarkIntent.CreateGroup(target.uuid, clone, null))
                is BookmarkNode.Process ->
                    viewModel.processIntent(BookmarkIntent.CreateProcess(target.uuid, clone, null))
            }
        }
    }

    private fun addToProcessStep() {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        val root = currentRoot ?: return
        val processes = collectProcesses(root).filter { process ->
            nodes.none { it.uuid == process.uuid || isDescendant(it, process.uuid) }
        }
        if (processes.isEmpty()) return
        val labels = processes.map { it.name.ifBlank { "(unnamed)" } }.toTypedArray()
        val choiceIndex = chooseIndex("Select process:", "Add Step", labels)
        val target = processes.getOrNull(choiceIndex) ?: return
        val steps = target.steps
        val positionOptions = mutableListOf("Append")
        steps.forEachIndexed { index, step ->
            positionOptions.add("Before ${index + 1}: ${step.name.ifBlank { "(unnamed)" }}")
        }
        val positionChoice = chooseIndex("Insert position:", "Add Step", positionOptions.toTypedArray())
        val baseIndex = if (positionChoice == 0) -1 else positionChoice - 1
        var insertIndex = baseIndex
        nodes.forEach { node ->
            val index = if (insertIndex < 0) -1 else insertIndex++
            viewModel.processIntent(BookmarkIntent.MoveNode(node.uuid, target.uuid, index))
        }
    }

    private fun cloneForInsert(node: BookmarkNode): BookmarkNode {
        return when (node) {
            is BookmarkNode.Bookmark -> BookmarkNode.Bookmark(
                name = node.name,
                description = node.description,
                filePath = node.filePath,
                line = node.line,
                column = node.column,
                iconPath = node.iconPath
            )
            is BookmarkNode.DescriptiveBookmark -> BookmarkNode.DescriptiveBookmark(
                name = node.name,
                description = node.description,
                markdownContent = node.markdownContent
            )
            is BookmarkNode.Group -> BookmarkNode.Group(
                name = node.name,
                description = node.description
            )
            is BookmarkNode.Process -> BookmarkNode.Process(
                name = node.name,
                description = node.description,
                entryFilePath = node.entryFilePath,
                entryLine = node.entryLine,
                markdownContent = node.markdownContent
            )
        }
    }

    private fun addToProcess() {
        val node = selectedNode() ?: return
        val root = currentRoot ?: return
        val processes = collectProcesses(root).filter { it.uuid != node.uuid }
        if (processes.isEmpty()) return
        val labels = processes.map { it.name.ifBlank { "(unnamed)" } }.toTypedArray()
        val choiceIndex = chooseIndex("Select process:", "Add To Process", labels)
        val target = processes.getOrNull(choiceIndex) ?: return
        viewModel.processIntent(BookmarkIntent.MoveNode(node.uuid, target.uuid, -1))
    }

    private fun syncReferences() {
        val source = selectedNode() as? BookmarkNode.Bookmark ?: return
        viewModel.processIntent(BookmarkIntent.SyncReferences(source.uuid))
    }

    private fun deleteReferences() {
        val source = selectedNode() as? BookmarkNode.Bookmark ?: return
        val confirmed = Messages.showYesNoDialog(
            project,
            "Delete all references for this bookmark?",
            "Delete References",
            Messages.getQuestionIcon()
        )
        if (confirmed == Messages.YES) {
            viewModel.processIntent(BookmarkIntent.DeleteReferences(source.uuid))
        }
    }

    private fun selectReferenceSource() {
        val target = selectedNode() as? BookmarkNode.Bookmark ?: return
        val root = currentRoot ?: return
        val sources = collectReferenceSources(target.uuid)
        if (sources.isEmpty()) return
        val labels = sources.map { formatBookmarkLabel(it) }.toTypedArray()
        val choiceIndex = chooseIndex("Select reference source:", "Reference Source", labels)
        val source = sources.getOrNull(choiceIndex) ?: return
        selectNodeById(source.uuid)
    }

    private fun selectReferenceTargets() {
        val source = selectedNode() as? BookmarkNode.Bookmark ?: return
        val root = currentRoot ?: return
        val targets = collectReferenceTargets(source.uuid)
        if (targets.isEmpty()) return
        val labels = targets.map { formatBookmarkLabel(it) }.toTypedArray()
        val choiceIndex = chooseIndex("Select reference target:", "Reference Targets", labels)
        val target = targets.getOrNull(choiceIndex) ?: return
        selectNodeById(target.uuid)
    }

    private fun chooseIndex(message: String, title: String, options: Array<String>): Int {
        return Messages.showDialog(project, message, title, options, 0, null)
    }

    private fun collectReferenceSources(targetId: String): List<BookmarkNode.Bookmark> {
        val root = currentRoot ?: return emptyList()
        val sourceIds = currentSourcesByTarget[targetId] ?: return emptyList()
        val all = collectBookmarks(root)
        return all.filter { sourceIds.contains(it.uuid) }
    }

    private fun collectReferenceTargets(sourceId: String): List<BookmarkNode.Bookmark> {
        val root = currentRoot ?: return emptyList()
        val targetIds = currentTargetsBySource[sourceId] ?: return emptyList()
        val all = collectBookmarks(root)
        return all.filter { targetIds.contains(it.uuid) }
    }

    private fun collectBookmarks(node: BookmarkNode): List<BookmarkNode.Bookmark> {
        val results = mutableListOf<BookmarkNode.Bookmark>()
        when (node) {
            is BookmarkNode.Bookmark -> results.add(node)
            is BookmarkNode.Group -> node.children.forEach { results.addAll(collectBookmarks(it)) }
            is BookmarkNode.Process -> node.steps.forEach { results.addAll(collectBookmarks(it)) }
            is BookmarkNode.DescriptiveBookmark -> Unit
        }
        return results
    }

    private fun formatGraphLabel(
        bookmark: BookmarkNode.Bookmark,
        pathMap: Map<String, String>
    ): String {
        val path = pathMap[bookmark.uuid] ?: bookmark.name
        return "$path (${bookmark.filePath}:${bookmark.line + 1})"
    }

    private fun buildPathMap(node: BookmarkNode, path: String = "Root"): Map<String, String> {
        val paths = mutableMapOf(node.uuid to path)
        val children = when (node) {
            is BookmarkNode.Group -> node.children
            is BookmarkNode.Process -> node.steps
            else -> emptyList()
        }
        children.forEach { child ->
            val name = child.name.ifBlank { "(unnamed)" }
            paths.putAll(buildPathMap(child, "$path/$name"))
        }
        return paths
    }

    private fun formatBookmarkLabel(bookmark: BookmarkNode.Bookmark): String {
        return "${bookmark.name} (${bookmark.filePath}:${bookmark.line + 1})"
    }

    private fun isDescendant(source: BookmarkNode, targetId: String): Boolean {
        return when (source) {
            is BookmarkNode.Group -> source.children.any { it.uuid == targetId || isDescendant(it, targetId) }
            is BookmarkNode.Process -> source.steps.any { it.uuid == targetId || isDescendant(it, targetId) }
            else -> false
        }
    }

    private fun collectContainers(node: BookmarkNode, path: String): List<ContainerItem> {
        val items = mutableListOf<ContainerItem>()
        when (node) {
            is BookmarkNode.Group -> {
                items.add(ContainerItem(node.uuid, "Group: $path"))
                node.children.forEach { child ->
                    items.addAll(collectContainers(child, "$path/${child.name}"))
                }
            }
            is BookmarkNode.Process -> {
                items.add(ContainerItem(node.uuid, "Process: $path"))
                node.steps.forEach { child ->
                    items.addAll(collectContainers(child, "$path/${child.name}"))
                }
            }
            else -> Unit
        }
        return items
    }

    private fun collectProcesses(node: BookmarkNode): List<BookmarkNode.Process> {
        val results = mutableListOf<BookmarkNode.Process>()
        when (node) {
            is BookmarkNode.Process -> results.add(node)
            is BookmarkNode.Group -> node.children.forEach { results.addAll(collectProcesses(it)) }
            is BookmarkNode.Bookmark -> Unit
            is BookmarkNode.DescriptiveBookmark -> Unit
        }
        return results
    }

    private fun deleteSelected() {
        val node = selectedNode() ?: return
        if (node.uuid == "root") return
        val warning = when (node) {
            is BookmarkNode.Bookmark -> {
                val outgoing = currentReferenceCounts[node.uuid] ?: 0
                val incoming = currentSourcesByTarget[node.uuid]?.size ?: 0
                when {
                    outgoing > 0 && incoming > 0 ->
                        "This bookmark has $outgoing outgoing references and is referenced by $incoming sources. Deleting will remove related references."
                    outgoing > 0 ->
                        "This bookmark has $outgoing outgoing references. Deleting will remove related references."
                    incoming > 0 ->
                        "This bookmark is referenced by $incoming sources. Deleting will remove related references."
                    else -> "Delete selected node?"
                }
            }
            else -> "Delete selected node?"
        }
        val confirmed = Messages.showYesNoDialog(
            project,
            warning,
            "Delete",
            Messages.getQuestionIcon()
        )
        if (confirmed == Messages.YES) {
            viewModel.processIntent(BookmarkIntent.DeleteNode(node.uuid))
        }
    }

    private fun selectedContainerId(): String? {
        return currentContainerId()
    }

    private fun createPopupMenu(): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(actionItem("Add Group") { createGroup() })
        menu.add(actionItem("Add Process") { createProcess() })
        menu.add(actionItem("Add Bookmark") { createBookmark() })
        menu.add(actionItem("Add Note") { createDescriptive() })
        menu.addSeparator()
        menu.add(actionItem("Edit") { editSelected() })
        menu.add(actionItem("Move") { moveSelected() })
        menu.addSeparator()
        menu.add(actionItem("Set Process Entry") { setProcessEntry() })
        menu.addSeparator()
        menu.add(actionItem("Create Reference") { createReference() })
        menu.add(actionItem("Select Reference Source") { selectReferenceSource() })
        menu.add(actionItem("Select Reference Targets") { selectReferenceTargets() })
        menu.add(actionItem("Add As Step") { addToProcessStep() })
        menu.add(actionItem("Add To Process") { addToProcess() })
        menu.add(actionItem("Copy To Process") { copyToProcess() })
        menu.add(actionItem("Show References") { showReferenceOverview() })
        menu.add(actionItem("Reference Graph") { showReferenceGraph() })
        menu.add(actionItem("Sync References") { syncReferences() })
        menu.add(actionItem("Delete References") { deleteReferences() })
        menu.addSeparator()
        menu.add(actionItem("Keymap Settings") { openKeymapSettings() })
        menu.addSeparator()
        menu.add(actionItem("Navigate") { navigateSelectedBookmark() })
        menu.add(actionItem("Delete") { deleteSelected() })
        menu.addSeparator()
        menu.add(actionItem("Prev in Process") {
            viewModel.processIntent(BookmarkIntent.NavigateToPrevInProcess)
        })
        menu.add(actionItem("Next in Process") {
            viewModel.processIntent(BookmarkIntent.NavigateToNextInProcess)
        })
        menu.addSeparator()
        menu.add(actionItem("Refresh") { viewModel.processIntent(BookmarkIntent.Refresh) })
        return menu
    }

    private fun actionItem(label: String, handler: () -> Unit) = javax.swing.JMenuItem(label).apply {
        addActionListener { handler() }
    }

    private fun openKeymapSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Keymap")
    }

    private fun checkShortcutConflicts() {
        val keymap = KeymapManager.getInstance().activeKeymap
        val actionIds = listOf(
            "CodeRemarkTour.CreateBookmarkAtCaret",
            "CodeRemarkTour.CreateGroupAtCaret",
            "CodeRemarkTour.CreateNoteAtCaret"
        )
        val conflicts = mutableListOf<String>()
        actionIds.forEach { actionId ->
            keymap.getShortcuts(actionId).forEach { shortcut ->
                val otherActions = keymap.getActionIds(shortcut).filter { it != actionId }
                if (otherActions.isNotEmpty()) {
                    val shortcutText = shortcut.toString().replace("pressed ", "")
                    val others = otherActions.joinToString(", ")
                    conflicts.add("$shortcutText -> $others")
                }
            }
        }
        if (conflicts.isNotEmpty()) {
            val preview = conflicts.take(3).joinToString("; ")
            val suffix = if (conflicts.size > 3) " (+${conflicts.size - 3})" else ""
            notify(
                "Shortcut conflicts detected: $preview$suffix. Open Keymap to adjust.",
                NotificationType.WARNING
            )
        }
    }

    private fun installShortcuts() {
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)) { deleteSelected() }
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.CTRL_DOWN_MASK)) { createGroup() }
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_P, KeyEvent.ALT_DOWN_MASK)) { createProcess() }
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK)) { createBookmark() }
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_N, KeyEvent.CTRL_DOWN_MASK)) { createDescriptive() }
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_E, KeyEvent.CTRL_DOWN_MASK)) { editSelected() }
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_M, KeyEvent.CTRL_DOWN_MASK)) { moveSelected() }
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_T, KeyEvent.CTRL_DOWN_MASK)) { addToProcess() }
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_T, KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK)) {
            copyToProcess()
        }
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_E, KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK)) {
            setProcessEntry()
        }
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK)) {
            viewModel.processIntent(BookmarkIntent.Refresh)
        }
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK)) {
            searchField.requestFocusInWindow()
        }
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_UP, KeyEvent.ALT_DOWN_MASK)) {
            viewModel.processIntent(BookmarkIntent.NavigateToPrevInProcess)
        }
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, KeyEvent.ALT_DOWN_MASK)) {
            viewModel.processIntent(BookmarkIntent.NavigateToNextInProcess)
        }
        bindShortcut(tree, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)) { navigateSelectedBookmark() }
        bindShortcut(searchField, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)) { clearSearchAndReturn() }
        bindShortcut(searchField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK)) { startSearch() }
    }

    private fun bindShortcut(component: JComponent, stroke: KeyStroke, action: () -> Unit) {
        val key = stroke.toString()
        val inputMap: InputMap = component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        inputMap.put(stroke, key)
        component.actionMap.put(key, object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                action()
            }
        })
    }

    private fun handleSideEffect(effect: BookmarkSideEffect) {
        when (effect) {
            is BookmarkSideEffect.NavigateToFile -> navigateToFile(effect.filePath, effect.line, effect.column)
            is BookmarkSideEffect.ShowNotification -> notify(effect.message, effect.type)
            is BookmarkSideEffect.ScrollToSelected -> Unit
            is BookmarkSideEffect.SelectNode -> {
                SelectionBus.getInstance(project).setLastSelectedNodeId(effect.nodeId)
                selectNodeById(effect.nodeId)
                tree.selectionPath?.let { tree.scrollPathToVisible(it) }
            }
            is BookmarkSideEffect.RefreshInlays -> {
                val normalizedPath = FileUtil.toSystemIndependentName(effect.filePath)
                val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedPath) ?: return
                val psiFile = PsiManager.getInstance(project).findFile(file)
                val analyzer = DaemonCodeAnalyzer.getInstance(project)
                if (psiFile != null) {
                    analyzer.restart(psiFile)
                } else {
                    analyzer.restart()
                }
            }
        }
    }

    private fun navigateSelectedBookmark() {
        val node = selectedNode() as? BookmarkNode.Bookmark ?: return
        viewModel.processIntent(BookmarkIntent.NavigateToBookmark(node))
    }

    private fun navigateToFile(filePath: String, line: Int, column: Int) {
        val file = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        OpenFileDescriptor(project, file, line.coerceAtLeast(0), column.coerceAtLeast(0)).navigate(true)
    }

    private fun ensureFileExists(path: String, title: String): Boolean {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return false
        val file = LocalFileSystem.getInstance().findFileByPath(trimmed)
        if (file == null) {
            Messages.showErrorDialog(project, "File not found: $trimmed", title)
            return false
        }
        return true
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeRemarkTour")
            .createNotification(message, type)
            .notify(project)
    }

    data class NodeView(
        val node: BookmarkNode,
        val referenceCount: Int,
        val isReferencedTarget: Boolean,
        val isSearchResult: Boolean,
        val pathLabel: String
    ) {
        val displayName: String = node.name.ifBlank { "(unnamed)" }
        val suffix: String = buildSuffix()
        val tooltip: String = buildTooltip()

        private fun buildSuffix(): String {
            val parts = mutableListOf<String>()
            if (referenceCount > 0) parts.add("refs:$referenceCount")
            if (isReferencedTarget) parts.add("ref")
            return if (parts.isEmpty()) "" else "[${parts.joinToString(", ")}]"
        }

        private fun buildTooltip(): String {
            val parts = mutableListOf<String>()
            parts.add(pathLabel)
            if (node is BookmarkNode.Bookmark) {
                parts.add("${node.filePath}:${node.line + 1}")
                if (node.description.isNotBlank()) {
                    parts.add(node.description.trim().replace("\n", " "))
                }
            } else if (node.description.isNotBlank()) {
                parts.add(node.description.trim().replace("\n", " "))
            }
            if (referenceCount > 0) parts.add("refs: $referenceCount")
            if (isReferencedTarget) parts.add("referenced")
            return parts.joinToString(" | ")
        }

        override fun toString(): String {
            return if (suffix.isEmpty()) displayName else "$displayName $suffix"
        }
    }

    private data class ContainerItem(val id: String, val label: String)
    private data class GraphNode(val label: String, val nodeId: String?) {
        override fun toString(): String = label
    }

    private companion object {
        private const val PLACEHOLDER_LABEL = "Loading..."
    }

    override fun dispose() {
        scope.cancel()
    }

    private inner class NodeTransferHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent): Int = MOVE

        override fun createTransferable(c: JComponent): Transferable? {
            val node = selectedNode() ?: return null
            if (node.uuid == "root") return null
            return StringSelection(node.uuid)
        }

        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDrop) return false
            if (!support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false
            val dropLocation = support.dropLocation as? javax.swing.JTree.DropLocation ?: return false
            val path = dropLocation.path ?: return false
            val targetNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return false
            val target = (targetNode.userObject as? NodeView)?.node
            return target != null
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            val nodeId = support.transferable.getTransferData(DataFlavor.stringFlavor) as? String ?: return false
            val dropLocation = support.dropLocation as? javax.swing.JTree.DropLocation ?: return false
            val path = dropLocation.path ?: return false
            val targetNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return false
            val target = (targetNode.userObject as? NodeView)?.node ?: return false

            val (parentId, index) = resolveDropTarget(targetNode, target, dropLocation.childIndex)
            if (parentId == null) return false
            if (nodeId == parentId) return false

            val root = currentRoot ?: return false
            val movedNode = findById(root, nodeId) ?: return false
            if (isDescendant(movedNode, parentId)) return false

            moveTreeNode(nodeId, parentId, index)
            viewModel.processIntent(BookmarkIntent.MoveNode(nodeId, parentId, index))
            return true
        }

        private fun resolveDropTarget(
            targetNode: DefaultMutableTreeNode,
            target: BookmarkNode,
            childIndex: Int
        ): Pair<String?, Int> {
            if (childIndex >= 0) {
                val parentNode = targetNode.parent as? DefaultMutableTreeNode ?: return null to -1
                val parent = (parentNode.userObject as? NodeView)?.node ?: return null to -1
                return parent.uuid to childIndex
            }
            return when (target) {
                is BookmarkNode.Group -> target.uuid to -1
                is BookmarkNode.Process -> target.uuid to -1
                else -> {
                    val parentNode = targetNode.parent as? DefaultMutableTreeNode ?: return null to -1
                    val parent = (parentNode.userObject as? NodeView)?.node ?: return null to -1
                    val index = parentNode.getIndex(targetNode)
                    parent.uuid to (index + 1)
                }
            }
        }

        private fun findById(node: BookmarkNode, id: String): BookmarkNode? {
            if (node.uuid == id) return node
            return when (node) {
                is BookmarkNode.Group -> node.children.firstNotNullOfOrNull { findById(it, id) }
                is BookmarkNode.Process -> node.steps.firstNotNullOfOrNull { findById(it, id) }
                else -> null
            }
        }
    }
}
