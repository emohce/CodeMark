package emohce.presentation.toolwindow.panel

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.FormBuilder
import emohce.data.datasource.BookmarkPersistentDataSource
import emohce.domain.model.BookmarkNode
import emohce.presentation.editor.highlighter.BookmarkHighlighterService
import emohce.presentation.index.BookmarkIndexService
import emohce.presentation.selection.SelectionBus
import emohce.presentation.toolwindow.BookmarkIntent
import emohce.presentation.toolwindow.BookmarkSideEffect
import emohce.presentation.toolwindow.BookmarkViewModel
import emohce.presentation.toolwindow.BookmarkViewState
import emohce.presentation.toolwindow.panel.render.BookmarkTreeCellRenderer
import emohce.presentation.toolwindow.panel.util.BookmarkTreeUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class BookmarkPanel(
    private val project: Project,
    private val viewModel: BookmarkViewModel
) : JPanel(BorderLayout()), Disposable {
    private val logger = Logger.getInstance(BookmarkPanel::class.java)
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Loading"))
    private lateinit var tree: Tree
    private lateinit var searchField: JTextField
    private lateinit var scope: CoroutineScope
    private val indexService = BookmarkIndexService.getInstance(project)
    private var currentRoot: BookmarkNode.Group? = null
    private var currentReferenceCounts: Map<String, Int> = emptyMap()
    private var currentReferenceTargets: Set<String> = emptySet()
    private var currentTargetsBySource: Map<String, List<String>> = emptyMap()
    private var currentSourcesByTarget: Map<String, List<String>> = emptyMap()
    private var currentPathMap: Map<String, String> = emptyMap()
    private var currentState: BookmarkViewState = BookmarkViewState()
    private var lastSelectedBeforeSearch: String? = null
    private var pendingSelectionAfterClear: String? = null
    private var isSelectingFromSideEffect: Boolean = false

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
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        searchField = JTextField(24)
        tree = Tree(treeModel)
        tree.isRootVisible = true
        val renderer = BookmarkTreeCellRenderer()
        tree.cellRenderer = renderer
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.addTreeSelectionListener {
            logger.info("=== TreeSelectionListener triggered ===")
            // 检查是否有选择，如果没有选择则直接返回
            val selectedPath = tree.selectionPath
            logger.info("TreeSelectionListener: selectedPath=${selectedPath != null}, isSelectingFromSideEffect=$isSelectingFromSideEffect")
            if (selectedPath == null) {
                logger.warn("TreeSelectionListener: no selection path, ignoring (this may happen when selection is cleared)")
                return@addTreeSelectionListener
            }
            
            logger.info("TreeSelectionListener: selection path exists, processing selection")
            
            // 如果是从 side effect 触发的选择，不执行导航
            if (isSelectingFromSideEffect) {
                logger.info("Selection is from side effect, skipping navigation")
                val node = selectedNode()
                logger.info("Selected node from side effect: ${node?.uuid}, type=${node?.javaClass?.simpleName}")
                if (node != null) {
                    viewModel.processIntent(BookmarkIntent.SelectNode(node.uuid))
                }
                SelectionBus.getInstance(project).setCurrentContainerId(currentContainerId())
                SelectionBus.getInstance(project).setLastSelectedNodeId(node?.uuid)
                return@addTreeSelectionListener
            }
            
            val node = selectedNode()
            logger.info("User clicked node: ${node?.uuid}, type=${node?.javaClass?.simpleName}")
            if (node != null) {
                viewModel.processIntent(BookmarkIntent.SelectNode(node.uuid))
                // 从最新的 state 中获取节点数据，确保使用最新数据
                val latestNode = currentRoot?.let { 
                    logger.info("Searching for node ${node.uuid} in currentRoot")
                    findNodeInTree(it, node.uuid) 
                } ?: node
                logger.info("Latest node found: ${latestNode.uuid}, type=${latestNode.javaClass.simpleName}, currentRoot=${currentRoot != null}")
                
                if (latestNode is BookmarkNode.Bookmark) {
                    logger.info("Navigating to bookmark: ${latestNode.filePath}:${latestNode.line}")
                    viewModel.processIntent(BookmarkIntent.NavigateToBookmark(latestNode))
                } else if (latestNode is BookmarkNode.Process) {
                    val entryPath = latestNode.entryFilePath
                    val entryLine = latestNode.entryLine
                    logger.info("Navigating to process entry: $entryPath:$entryLine")
                    if (!entryPath.isNullOrBlank() && entryLine != null) {
                        navigateToFile(entryPath, entryLine, 0)
                    } else {
                        logger.warn("Process entry path or line is null: path=$entryPath, line=$entryLine")
                    }
                } else {
                    logger.info("Node is not a Bookmark or Process, skipping navigation")
                }
            } else {
                logger.warn("Selected node is null after checking selection path")
            }
            SelectionBus.getInstance(project).setCurrentContainerId(currentContainerId())
            SelectionBus.getInstance(project).setLastSelectedNodeId(node?.uuid)
            renderer.highlightNodeId = node?.uuid
            tree.repaint()
        }
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "collapse")
        tree.actionMap.put("collapse", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val path = tree.selectionPath ?: return
                if (tree.isExpanded(path)) {
                    tree.collapsePath(path)
                } else {
                    // move selection to parent when already collapsed
                    path.parentPath?.let { tree.selectionPath = it }
                }
            }
        })
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "expand")
        tree.actionMap.put("expand", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                    val path = tree.selectionPath ?: run {
                        if (tree.rowCount > 0) tree.setSelectionRow(0)
                        return
                    }
                    if (!tree.isExpanded(path)) {
                        tree.expandPath(path)
                    } else {
                        // if already expanded, move to first child
                        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                        if (node.childCount > 0) {
                            val child = node.getChildAt(0) as? DefaultMutableTreeNode ?: return
                            tree.selectionPath = TreePath(child.path)
                        }
                    }
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
                logger.info("Mouse pressed: button=${e.button}, isPopupTrigger=${e.isPopupTrigger}, clickCount=${e.clickCount}")
                if (e.isPopupTrigger || e.button == MouseEvent.BUTTON1) selectNodeAt(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                logger.info("Mouse released: button=${e.button}, isPopupTrigger=${e.isPopupTrigger}, clickCount=${e.clickCount}")
                if (e.isPopupTrigger) selectNodeAt(e)
            }

            override fun mouseClicked(e: MouseEvent) {
                logger.info("Mouse clicked: button=${e.button}, clickCount=${e.clickCount}, isSelectingFromSideEffect=$isSelectingFromSideEffect")
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) {
                    // 保障在节点被删除后重新点击时也能正确选中
                    if (tree.selectionPath == null) {
                        selectNodeAt(e)
                    }
                    logger.info("Single left click detected, navigating to bookmark")
                    navigateSelectedBookmark()
                }
            }
        })

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        val addGroup = JButton("Add Group")
        val addProcess = JButton("Add Process")
        val addBookmark = JButton("Add CodeMark")
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

        installSearchFieldListeners()

        viewModel.state.onEach { state ->
            updateTree(state)
        }.launchIn(scope)

        viewModel.sideEffects.onEach { effect ->
            handleSideEffect(effect)
        }.launchIn(scope)

        SelectionBus.getInstance(project).requests.onEach { req ->
            viewModel.processIntent(BookmarkIntent.Refresh)
            val found = selectNodeById(req.nodeId)
            if (found) {
                SelectionBus.getInstance(project).setLastSelectedNodeId(req.nodeId)
                selectNodeWithRetry(req.nodeId)
            } else {
                // Gutter click: expand by domain path so inner nodes under collapsed groups are selected
                if (expandToNodeByDomainPath(req.nodeId)) {
                    SelectionBus.getInstance(project).setLastSelectedNodeId(req.nodeId)
                    renderer.highlightNodeId = req.nodeId
                    tree.repaint()
                    return@onEach
                }
                val byIndex = req.filePath?.let { path -> indexService.firstMatch(path, req.line)?.nodeId }
                val resolvedId = byIndex
                if (resolvedId != null) {
                    SelectionBus.getInstance(project).setLastSelectedNodeId(resolvedId)
                    selectNodeWithRetry(resolvedId)
                } else if (req.filePath != null) {
                    if (selectNodeByPathAndLine(req.filePath, req.line)) {
                        selectedNode()?.uuid?.let {
                            SelectionBus.getInstance(project).setLastSelectedNodeId(it)
                            renderer.highlightNodeId = it
                            tree.repaint()
                        }
                    }
                }
            }
        }.launchIn(scope)

        SwingUtilities.invokeLater { searchField.requestFocusInWindow() }
    }

    private fun installSearchFieldListeners() {
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = liveSearch()
            override fun removeUpdate(e: DocumentEvent) = liveSearch()
            override fun changedUpdate(e: DocumentEvent) = liveSearch()
        })

        tree.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyTyped(e: java.awt.event.KeyEvent) {
                if (!e.isControlDown && !e.isAltDown && !e.isMetaDown && !e.isActionKey) {
                    if (!searchField.hasFocus()) {
                        searchField.requestFocusInWindow()
                    }
                }
            }
        })
    }

    private fun liveSearch() {
        val query = searchField.text
        if (query.isBlank()) {
            viewModel.processIntent(BookmarkIntent.ClearSearch)
        } else {
            viewModel.processIntent(BookmarkIntent.Search(query, activeFilters()))
        }
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

    /** Repaint selected row without stealing focus (keep editor focus). */
    private fun highlightSelectedRow() {
        tree.selectionPath?.let { path ->
            tree.getPathBounds(path)?.let { tree.repaint(it) }
        }
    }

    /** Select node by file path and (optional) line number; expands path but does not steal focus. */
    private fun selectNodeByPathAndLine(filePath: String, line: Int?): Boolean {
        val normalized = FileUtil.toSystemIndependentName(filePath)
        val rootNode = treeModel.root as? DefaultMutableTreeNode ?: return false
        val target = BookmarkTreeUtil.findNode(rootNode) { view ->
            val node = view.node
            when (node) {
                is BookmarkNode.Bookmark -> FileUtil.toSystemIndependentName(node.filePath) == normalized && (line == null || node.line == line)
                is BookmarkNode.Process -> node.entryFilePath != null && FileUtil.toSystemIndependentName(node.entryFilePath) == normalized && (line == null || node.entryLine == line)
                else -> false
            }
        } ?: return false

        val path = TreePath(target.path)
        tree.selectionPath = path
        tree.expandPath(path)
        tree.scrollPathToVisible(path)
        highlightSelectedRow()
        return true
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
                treeNode.add(BookmarkTreeUtil.createPlaceholderNode())
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
        val selected = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        if (selected == null) {
            // 没有选择是正常情况，使用 debug 级别
            logger.debug("selectedNode: tree.lastSelectedPathComponent is null or not DefaultMutableTreeNode")
            return null
        }
        val nodeView = selected.userObject as? NodeView
        if (nodeView == null) {
            // userObject 不是 NodeView 可能是异常情况，记录警告
            logger.warn("selectedNode: userObject is null or not NodeView, userObject=${selected.userObject?.javaClass?.simpleName}")
            return null
        }
        val node = nodeView.node
        logger.debug("selectedNode: found node ${node.uuid}, type=${node.javaClass.simpleName}")
        return node
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
        val selectedTreeNode = lastSelectedId?.let { BookmarkTreeUtil.findNodeById(root, it) }
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
        if (BookmarkTreeUtil.hasPlaceholder(node)) return 0
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
        logger.info("selectNodeAt: x=${event.x}, y=${event.y}")
        val row = tree.getClosestRowForLocation(event.x, event.y)
        logger.info("selectNodeAt: closest row=$row")
        if (row >= 0) {
            logger.info("selectNodeAt: setting selection row=$row")
            tree.setSelectionRow(row)
        } else {
            logger.warn("selectNodeAt: row < 0, cannot set selection")
        }
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

    private fun selectNodeById(nodeId: String): Boolean {
        logger.info("selectNodeById: searching for nodeId=$nodeId")
        val success = BookmarkTreeUtil.selectNodeById(tree, treeModel, nodeId)
        if (!success) {
            logger.warn("selectNodeById: node not found for nodeId=$nodeId")
        } else {
            logger.info("selectNodeById: completed successfully")
        }
        return success
    }

    /** Domain path from root to node with [targetId] (inclusive), or null if not found. */
    private fun pathFromRootTo(node: BookmarkNode, targetId: String): List<BookmarkNode>? {
        if (node.uuid == targetId) return listOf(node)
        return when (node) {
            is BookmarkNode.Group -> {
                for (c in node.children) {
                    val sub = pathFromRootTo(c, targetId) ?: continue
                    return listOf(node) + sub
                }
                null
            }
            is BookmarkNode.Process -> {
                for (s in node.steps) {
                    val sub = pathFromRootTo(s, targetId) ?: continue
                    return listOf(node) + sub
                }
                null
            }
            else -> null
        }
    }

    /**
     * Expand tree along domain path to [nodeId] and select that node (for gutter click).
     * Populates lazy nodes so deep bookmarks under collapsed groups are found.
     */
    private fun expandToNodeByDomainPath(nodeId: String): Boolean {
        val rootNode = currentRoot ?: return false
        val path = pathFromRootTo(rootNode, nodeId) ?: return false
        if (path.isEmpty()) return false
        val treeRoot = treeModel.root as? DefaultMutableTreeNode ?: return false
        if (path.size == 1) {
            val pathTree = TreePath(treeRoot.path)
            tree.selectionPath = pathTree
            tree.expandPath(pathTree)
            tree.scrollPathToVisible(pathTree)
            highlightSelectedRow()
            isSelectingFromSideEffect = true
            javax.swing.SwingUtilities.invokeLater { isSelectingFromSideEffect = false }
            return true
        }
        var currentTreeNode = treeRoot
        for (i in 1 until path.size) {
            val domainNode = path[i]
            if (BookmarkTreeUtil.hasPlaceholder(currentTreeNode)) {
                populateChildren(currentTreeNode, path[i - 1])
            }
            val childTreeNode = BookmarkTreeUtil.nodeChildren(currentTreeNode).firstOrNull {
                BookmarkTreeUtil.getNodeView(it)?.node?.uuid == domainNode.uuid
            } ?: return false
            tree.expandPath(TreePath(childTreeNode.path))
            currentTreeNode = childTreeNode
        }
        tree.selectionPath = TreePath(currentTreeNode.path)
        tree.expandPath(tree.selectionPath)
        tree.scrollPathToVisible(tree.selectionPath)
        highlightSelectedRow()
        return true
    }
    
    /**
     * 带重试机制的节点选择
     * 因为树更新可能是异步的，需要等待树更新完成后再选择节点
     */
    private fun selectNodeWithRetry(nodeId: String, maxRetries: Int = 5, delayMs: Long = 100) {
        logger.info("[SELECT_NODE_RETRY] Starting selectNodeWithRetry for nodeId=$nodeId, maxRetries=$maxRetries")
        var attempt = 0
        
        fun trySelect() {
            attempt++
            logger.info("[SELECT_NODE_RETRY] Attempt $attempt/$maxRetries")
            
            // 设置标志，防止触发导航
            isSelectingFromSideEffect = true
            
            val success = selectNodeById(nodeId)
            
            if (success) {
                logger.info("[SELECT_NODE_RETRY] Node selected successfully on attempt $attempt")
                
                // 确保展开到节点路径，包括所有父节点
                tree.selectionPath?.let { path ->
                    logger.info("[SELECT_NODE_RETRY] Expanding path for nodeId=$nodeId")
                    // 展开路径上的所有节点，从当前节点向上到根节点
                    var currentPath: TreePath? = path
                    while (currentPath != null && currentPath.pathCount > 0) {
                        try {
                            tree.expandPath(currentPath)
                            val parent = currentPath.parentPath
                            if (parent == null || parent.pathCount == 0) {
                                // 到达根节点，停止展开
                                break
                            }
                            currentPath = parent
                        } catch (e: Exception) {
                            logger.warn("[SELECT_NODE_RETRY] Error expanding path: ${e.message}", e)
                            break
                        }
                    }
                    tree.scrollPathToVisible(path)
                } ?: logger.warn("[SELECT_NODE_RETRY] tree.selectionPath is null after selectNodeById")
                highlightSelectedRow()
                
                // 在下一个事件循环中清除标志
                javax.swing.SwingUtilities.invokeLater {
                    logger.info("[SELECT_NODE_RETRY] Clearing isSelectingFromSideEffect flag")
                    isSelectingFromSideEffect = false
                }
            } else {
                if (attempt < maxRetries) {
                    logger.info("[SELECT_NODE_RETRY] Node not found, will retry in ${delayMs}ms (attempt $attempt/$maxRetries)")
                    // 使用 Timer 延迟重试，避免阻塞 EDT
                    javax.swing.Timer(delayMs.toInt()) {
                        trySelect()
                    }.apply {
                        isRepeats = false
                        start()
                    }
                } else {
                    logger.warn("[SELECT_NODE_RETRY] Node not found after $maxRetries attempts, giving up")
                    isSelectingFromSideEffect = false
                }
            }
        }
        
        // 首次尝试延迟执行，确保树更新完成
        javax.swing.SwingUtilities.invokeLater {
            trySelect()
        }
    }

    private fun restoreExpandedNodes(expandedIds: Set<String>) {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return
        expandedIds.forEach { id ->
            val node = BookmarkTreeUtil.findNodeById(root, id) ?: return@forEach
            val view = BookmarkTreeUtil.getNodeView(node)
            if (view != null) {
                populateChildren(node, view.node)
            }
            val path = TreePath(node.path)
            tree.expandPath(path)
        }
    }

    private fun populateChildren(treeNode: DefaultMutableTreeNode, node: BookmarkNode) {
        if (!BookmarkTreeUtil.hasPlaceholder(treeNode)) return
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


    private fun buildPathLabel(node: BookmarkNode): String {
        return currentPathMap[node.uuid] ?: "Root/${node.name.ifBlank { "(unnamed)" }}"
    }

    private fun applyDiff(
        model: DefaultTreeModel,
        current: DefaultMutableTreeNode,
        updated: DefaultMutableTreeNode
    ): Boolean {
        if (BookmarkTreeUtil.nodeKey(current) != BookmarkTreeUtil.nodeKey(updated)) return false

        // 更新当前节点的 userObject（NodeView），确保节点数据是最新的
        val currentView = current.userObject as? NodeView
        val updatedView = updated.userObject as? NodeView
        if (currentView != null && updatedView != null && currentView.node.uuid == updatedView.node.uuid) {
            // 如果节点数据已变化，更新 userObject
            if (currentView.node != updatedView.node || 
                currentView.referenceCount != updatedView.referenceCount ||
                currentView.isReferencedTarget != updatedView.isReferencedTarget) {
                current.userObject = updatedView
                model.nodeChanged(current)
            }
        }

        val existingChildren = BookmarkTreeUtil.nodeChildren(current)
        val existingMap = existingChildren.associateBy { BookmarkTreeUtil.nodeKey(it) }.toMutableMap()
        val desiredKeys = mutableListOf<String>()

        val updatedChildren = BookmarkTreeUtil.nodeChildren(updated)
        updatedChildren.forEachIndexed { index, updatedChild ->
            val key = BookmarkTreeUtil.nodeKey(updatedChild)
            desiredKeys.add(key)
            val existing = existingMap.remove(key)
            if (existing == null) {
                val copy = BookmarkTreeUtil.copyNode(updatedChild)
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


    private fun moveTreeNode(nodeId: String, parentId: String, index: Int) {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return
        val node = BookmarkTreeUtil.findNodeById(root, nodeId) ?: return
        val parent = BookmarkTreeUtil.findNodeById(root, parentId) ?: return

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
        scope.launch {
            val (parentId, insertIndex) = viewModel.getInsertionTarget(SelectionBus.getInstance(project).getLastSelectedNodeId())
            withContext(Dispatchers.Main) {
                val name = Messages.showInputDialog(project, "Group name:", "Create Group", null) ?: return@withContext
                if (name.isBlank()) return@withContext
                val group = BookmarkNode.Group(name = name.trim())
                viewModel.processIntent(BookmarkIntent.CreateGroup(parentId, group, insertIndex))
                SelectionBus.getInstance(project).requestSelect(group.uuid)
            }
        }
    }

    private fun createProcess() {
        scope.launch {
            val (parentId, insertIndex) = viewModel.getInsertionTarget(SelectionBus.getInstance(project).getLastSelectedNodeId())
            withContext(Dispatchers.Main) {
                val name = Messages.showInputDialog(project, "Process name:", "Create Process", null) ?: return@withContext
                if (name.isBlank()) return@withContext
                val description = Messages.showInputDialog(project, "Description:", "Create Process", null) ?: ""
                val entryPath = Messages.showInputDialog(project, "Entry file path (optional):", "Create Process", null)
                val entryLineText = Messages.showInputDialog(project, "Entry line (optional):", "Create Process", null)
                val entryLine = entryLineText?.toIntOrNull()
                if (!entryPath.isNullOrBlank() && !ensureFileExists(entryPath, "Create Process")) return@withContext
                val process = BookmarkNode.Process(
                    name = name.trim(),
                    description = description,
                    entryFilePath = entryPath?.takeIf { it.isNotBlank() },
                    entryLine = entryLine
                )
                viewModel.processIntent(BookmarkIntent.CreateProcess(parentId, process, insertIndex))
                SelectionBus.getInstance(project).requestSelect(process.uuid)
            }
        }
    }

    /** Default file path for new bookmark: same file as selected node or its container (so new bookmark is in that directory). */
    private fun defaultFilePathForNewBookmark(parentId: String? = null): String? {
        val selected = selectedNode()
        val fromSelected = when (selected) {
            is BookmarkNode.Bookmark -> selected.filePath
            is BookmarkNode.Process -> selected.entryFilePath
            else -> null
        }
        if (!fromSelected.isNullOrBlank()) return fromSelected
        val pid = parentId ?: insertionTarget().first ?: return null
        val parentNode = currentRoot?.let { findNodeInTree(it, pid) } ?: return null
        return when (parentNode) {
            is BookmarkNode.Bookmark -> parentNode.filePath
            is BookmarkNode.Process -> parentNode.entryFilePath
            else -> null
        }
    }

    private fun createBookmark() {
        scope.launch {
            val (parentId, insertIndex) = viewModel.getInsertionTarget(SelectionBus.getInstance(project).getLastSelectedNodeId())
            withContext(Dispatchers.Main) {
                val name = Messages.showInputDialog(project, "CodeMark name:", "Create CodeMark", null) ?: return@withContext
                if (name.isBlank()) return@withContext
                val defaultPath = defaultFilePathForNewBookmark(parentId)
                val pathPrompt = if (!defaultPath.isNullOrBlank()) "File path:\n(Default: $defaultPath)" else "File path:"
                val filePath = Messages.showInputDialog(project, pathPrompt, "Create CodeMark", null) ?: return@withContext
                if (!ensureFileExists(filePath, "Create CodeMark")) return@withContext
                val lineText = Messages.showInputDialog(project, "Line number:", "Create CodeMark", null) ?: "0"
                val line = (lineText.toIntOrNull() ?: 0).coerceAtLeast(0)
                val bookmark = BookmarkNode.Bookmark(name = name.trim(), filePath = filePath.trim(), line = line)
                viewModel.processIntent(BookmarkIntent.CreateBookmark(parentId, bookmark, insertIndex))
                SelectionBus.getInstance(project).requestSelect(bookmark.uuid)
            }
        }
    }

    private fun createDescriptive() {
        scope.launch {
            val (parentId, insertIndex) = viewModel.getInsertionTarget(SelectionBus.getInstance(project).getLastSelectedNodeId())
            withContext(Dispatchers.Main) {
                val name = Messages.showInputDialog(project, "Note title:", "Create Note", null) ?: return@withContext
                if (name.isBlank()) return@withContext
                val description = Messages.showInputDialog(project, "Description:", "Create Note", null) ?: ""
                val markdown = Messages.showInputDialog(project, "Markdown:", "Create Note", null) ?: ""
                val note = BookmarkNode.DescriptiveBookmark(
                    name = name.trim(),
                    description = description.trim(),
                    markdownContent = markdown
                )
                viewModel.processIntent(BookmarkIntent.CreateDescriptive(parentId, note, insertIndex))
                SelectionBus.getInstance(project).requestSelect(note.uuid)
            }
        }
    }

    private fun editSelected() {
        val node = selectedNode() ?: return
        if (node.uuid == "root") return
        // 从最新的 state 中获取节点数据，确保使用最新数据
        val latestNode = currentRoot?.let { findNodeInTree(it, node.uuid) } ?: node
        val updated = when (latestNode) {
            is BookmarkNode.Bookmark -> editBookmark(latestNode)
            is BookmarkNode.DescriptiveBookmark -> editDescriptive(latestNode)
            is BookmarkNode.Group -> editGroup(latestNode)
            is BookmarkNode.Process -> editProcess(latestNode)
        } ?: return
        viewModel.processIntent(BookmarkIntent.EditNode(updated))
    }
    
    private fun findNodeInTree(root: BookmarkNode, targetId: String): BookmarkNode? {
        logger.debug("findNodeInTree: searching for $targetId in root ${root.uuid}, type=${root.javaClass.simpleName}")
        if (root.uuid == targetId) {
            logger.debug("findNodeInTree: found node ${root.uuid} at root")
            return root
        }
        val result = when (root) {
            is BookmarkNode.Group -> {
                logger.debug("findNodeInTree: searching in Group with ${root.children.size} children")
                root.children.firstNotNullOfOrNull { findNodeInTree(it, targetId) }
            }
            is BookmarkNode.Process -> {
                logger.debug("findNodeInTree: searching in Process with ${root.steps.size} steps")
                root.steps.firstNotNullOfOrNull { findNodeInTree(it, targetId) }
            }
            else -> {
                logger.debug("findNodeInTree: root is not Group or Process, returning null")
                null
            }
        }
        if (result != null) {
            logger.debug("findNodeInTree: found node ${result.uuid}")
        } else {
            logger.debug("findNodeInTree: node $targetId not found in subtree of ${root.uuid}")
        }
        return result
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

        if (!showPanelOkCancel(panel, "Edit CodeMark")) return null
        val path = pathField.text.trim()
        if (!ensureFileExists(path, "Edit CodeMark")) return null
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
        val choiceIndex = chooseIndex("Select target codemark:", "Create Reference", labels)
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
            "Delete all references for this codemark?",
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
                        "This codemark has $outgoing outgoing references and is referenced by $incoming sources. Deleting will remove related references."
                    outgoing > 0 ->
                        "This codemark has $outgoing outgoing references. Deleting will remove related references."
                    incoming > 0 ->
                        "This codemark is referenced by $incoming sources. Deleting will remove related references."
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
//        menu.add(actionItem("Add Process") { createProcess() })
        menu.add(actionItem("Add CodeMark") { createBookmark() })
        menu.add(actionItem("Add Note") { createDescriptive() })
        menu.addSeparator()
        menu.add(actionItem("Edit") { editSelected() })
        menu.add(actionItem("Move") { moveSelected() })
        menu.addSeparator()
//        menu.add(actionItem("Set Process Entry") { setProcessEntry() })
//        menu.addSeparator()
//        menu.add(actionItem("Create Reference") { createReference() })
//        menu.add(actionItem("Select Reference Source") { selectReferenceSource() })
//        menu.add(actionItem("Select Reference Targets") { selectReferenceTargets() })
//        menu.add(actionItem("Add As Step") { addToProcessStep() })
//        menu.add(actionItem("Add To Process") { addToProcess() })
//        menu.add(actionItem("Copy To Process") { copyToProcess() })
//        menu.add(actionItem("Show References") { showReferenceOverview() })
//        menu.add(actionItem("Reference Graph") { showReferenceGraph() })
//        menu.add(actionItem("Sync References") { syncReferences() })
//        menu.add(actionItem("Delete References") { deleteReferences() })
//        menu.addSeparator()
//        menu.add(actionItem("Keymap Settings") { openKeymapSettings() })
//        menu.addSeparator()
//        menu.add(actionItem("Navigate") { navigateSelectedBookmark() })
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
            is BookmarkSideEffect.NavigateToFile -> {
                logger.info("NavigateToFile side effect received: filePath=${effect.filePath}, line=${effect.line}, column=${effect.column}")
                navigateToFile(effect.filePath, effect.line, effect.column)
            }
            is BookmarkSideEffect.ShowNotification -> notify(effect.message, effect.type)
            is BookmarkSideEffect.ScrollToSelected -> Unit
            is BookmarkSideEffect.SelectNode -> {
                logger.info("[SELECT_NODE] Side effect received: nodeId=${effect.nodeId}")
                SelectionBus.getInstance(project).setLastSelectedNodeId(effect.nodeId)
                // 使用重试机制，因为树更新可能是异步的
                selectNodeWithRetry(effect.nodeId, maxRetries = 5, delayMs = 100)
            }
            is BookmarkSideEffect.RefreshInlays -> {
                // Gutter + line-end painter: refresh highlighters and repaint (EditorLinePainter reads index on paint)
                BookmarkHighlighterService.getInstance(project).refreshGutterForFile(effect.filePath)
            }
            is BookmarkSideEffect.RefreshGutterAll -> {
                // Gutter 由 BookmarkHighlighterService 单源刷新（RangeHighlighter），不再使用 LineMarkerProvider/daemon
            }
            is BookmarkSideEffect.RefreshGutterForFile -> {
                BookmarkHighlighterService.getInstance(project).refreshGutterForFile(effect.filePath)
            }
            is BookmarkSideEffect.RefreshBookmarkxJson -> {
                // 刷新打开的 codemark.json 文件编辑器
                val basePath = project.basePath ?: return
                val bookmarkxPath = BookmarkPersistentDataSource.dataPath(basePath)
                val normalizedPath = FileUtil.toSystemIndependentName(bookmarkxPath.toString())
                val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedPath) ?: return
                
                // 使用 WriteIntentReadAction 来刷新文件
                WriteIntentReadAction.run<Nothing> {
                    // 如果文件已打开，重新加载文档内容
                    val fileEditorManager = FileEditorManager.getInstance(project)
                    if (fileEditorManager.isFileOpen(file)) {
                        // 刷新 VFS 文件
                        file.refresh(false, false)
                        // 重新加载文档
                        val documentManager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                        val document = documentManager.getDocument(file)
                        if (document != null) {
                            // 重新从磁盘加载文件内容到文档
                            documentManager.reloadFiles(file)
                        }
                    }
                }
            }
        }
    }

    private fun navigateSelectedBookmark() {
        val node = selectedNode() as? BookmarkNode.Bookmark ?: return
        viewModel.processIntent(BookmarkIntent.NavigateToBookmark(node))
    }

    private fun navigateToFile(filePath: String, line: Int, column: Int) {
        logger.info("navigateToFile called: filePath=$filePath, line=$line, column=$column")
        val file = LocalFileSystem.getInstance().findFileByPath(filePath)
        if (file == null) {
            logger.error("navigateToFile: file not found for path=$filePath")
            return
        }
        logger.info("navigateToFile: file found, opening file and scrolling to line=$line without moving cursor")
        
        // 打开文件但不请求焦点（保持焦点在树组件上，以便识别快捷键）
        val fileEditorManager = FileEditorManager.getInstance(project)
        fileEditorManager.openFile(file, false)
        
        // 使用 invokeLater 确保编辑器已完全初始化
        javax.swing.SwingUtilities.invokeLater {
            // 获取文本编辑器并滚动到指定行（居中显示），但不移动光标
            val editors = fileEditorManager.getEditors(file)
            editors.forEach { editor ->
                (editor as? TextEditor)?.editor?.let { textEditor ->
                    val targetLine = line.coerceAtLeast(0)
                    val document = textEditor.document
                    if (targetLine < document.lineCount) {
                        // 保存当前光标位置
                        val primaryCaret = textEditor.caretModel.primaryCaret
                        val savedOffset = primaryCaret.offset
                        
                        // 临时移动光标到目标行（用于滚动）
                        val targetOffset = document.getLineStartOffset(targetLine)
                        primaryCaret.moveToOffset(targetOffset)
                        
                        // 滚动到光标位置并居中显示
                        textEditor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                        
                        // 恢复原来的光标位置
                        primaryCaret.moveToOffset(savedOffset)
                        
                        logger.info("navigateToFile: scrolled to line $targetLine and restored cursor position")
                    }
                }
            }
            BookmarkHighlighterService.getInstance(project).flashLineForFile(filePath, line.coerceAtLeast(0))
            logger.info("navigateToFile: navigation completed")
        }
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
