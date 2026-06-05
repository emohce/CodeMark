package emohce.presentation.toolwindow.panel.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.treeStructure.Tree
import emohce.domain.model.BookmarkNode
import emohce.presentation.toolwindow.panel.BookmarkPanel.NodeView
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * 书签树操作工具类
 * 
 * 封装树节点的查找、选择、展开等常用操作，提高代码可维护性和复用性。
 */
object BookmarkTreeUtil {
    private val logger = Logger.getInstance(BookmarkTreeUtil::class.java)
    
    private const val PLACEHOLDER_LABEL = "Loading..."

    /**
     * 根据节点 ID 查找树节点
     * 
     * @param root 根节点
     * @param nodeId 要查找的节点 ID
     * @return 找到的节点，如果不存在则返回 null
     */
    fun findNodeById(root: DefaultMutableTreeNode, nodeId: String): DefaultMutableTreeNode? {
        return findNodeByIdInternal(root, nodeId, 0)
    }
    
    private fun findNodeByIdInternal(node: DefaultMutableTreeNode, nodeId: String, depth: Int): DefaultMutableTreeNode? {
        val view = node.userObject as? NodeView
        val currentNodeId = view?.node?.uuid
        if (currentNodeId == nodeId) {
            logger.debug("[TREE_FIND] Found node at depth=$depth, nodeId=$nodeId")
            return node
        }
        
        val childCount = node.childCount
        if (childCount > 0) {
            logger.debug("[TREE_FIND] Checking node at depth=$depth, currentNodeId=$currentNodeId, childCount=$childCount")
            val children = node.children()
            while (children.hasMoreElements()) {
                val child = children.nextElement() as? DefaultMutableTreeNode ?: continue
                val match = findNodeByIdInternal(child, nodeId, depth + 1)
                if (match != null) return match
            }
        }
        return null
    }

    /**
     * 根据条件查找树节点
     * 
     * @param root 根节点
     * @param predicate 匹配条件
     * @return 找到的节点，如果不存在则返回 null
     */
    fun findNode(root: DefaultMutableTreeNode, predicate: (NodeView) -> Boolean): DefaultMutableTreeNode? {
        val view = root.userObject as? NodeView
        if (view != null && predicate(view)) return root
        
        val children = root.children()
        while (children.hasMoreElements()) {
            val child = children.nextElement() as? DefaultMutableTreeNode ?: continue
            val match = findNode(child, predicate)
            if (match != null) return match
        }
        return null
    }

    /**
     * 选择并展开指定节点
     * 
     * @param tree 树组件
     * @param treeModel 树模型
     * @param nodeId 要选择的节点 ID
     * @return 是否成功选择节点
     */
    fun selectNodeById(tree: Tree, treeModel: DefaultTreeModel, nodeId: String): Boolean {
        logger.debug("[TREE_SELECT] Starting selectNodeById for nodeId=$nodeId")
        val root = treeModel.root as? DefaultMutableTreeNode
        if (root == null) {
            logger.warn("[TREE_SELECT] Root is null")
            return false
        }
        
        val rootView = root.userObject as? NodeView
        logger.debug("[TREE_SELECT] Root nodeId=${rootView?.node?.uuid}, root childCount=${root.childCount}")
        
        val node = findNodeById(root, nodeId)
        if (node == null) {
            logger.warn("[TREE_SELECT] Node not found in tree for nodeId=$nodeId")
            // 尝试列出所有节点ID以便调试
            logger.debug("[TREE_SELECT] Listing all node IDs in tree:")
            listAllNodeIds(root, 0)
            return false
        }
        
        logger.debug("[TREE_SELECT] Node found, creating TreePath...")
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.expandPath(path)
        tree.scrollPathToVisible(path)
        logger.debug("[TREE_SELECT] Node selected successfully")
        return true
    }
    
    private fun listAllNodeIds(node: DefaultMutableTreeNode, depth: Int) {
        val view = node.userObject as? NodeView
        val nodeId = view?.node?.uuid
        val indent = "  ".repeat(depth)
        logger.debug("$indent- nodeId=$nodeId, type=${view?.node?.javaClass?.simpleName}, childCount=${node.childCount}")
        
        val children = node.children()
        while (children.hasMoreElements()) {
            val child = children.nextElement() as? DefaultMutableTreeNode ?: continue
            listAllNodeIds(child, depth + 1)
        }
    }

    /**
     * 选择并展开指定节点（使用节点对象）
     * 
     * @param tree 树组件
     * @param node 要选择的节点
     * @return 是否成功选择节点
     */
    fun selectNode(tree: Tree, node: DefaultMutableTreeNode): Boolean {
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.expandPath(path)
        tree.scrollPathToVisible(path)
        return true
    }

    /**
     * 展开到指定节点（展开路径上的所有父节点）
     * 
     * @param tree 树组件
     * @param nodeId 目标节点 ID
     * @param treeModel 树模型
     * @return 是否成功展开
     */
    fun expandToNode(tree: Tree, treeModel: DefaultTreeModel, nodeId: String): Boolean {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return false
        val node = findNodeById(root, nodeId) ?: return false
        
        val path = TreePath(node.path)
        tree.expandPath(path)
        return true
    }

    /**
     * 获取节点的键值（用于节点比较和映射）
     * 
     * @param node 树节点
     * @return 节点键值（通常是节点 UUID）
     */
    fun nodeKey(node: DefaultMutableTreeNode): String {
        val obj = node.userObject
        val view = obj as? NodeView
        return view?.node?.uuid ?: obj?.toString().orEmpty()
    }

    /**
     * 获取节点的所有子节点列表
     * 
     * @param node 父节点
     * @return 子节点列表
     */
    fun nodeChildren(node: DefaultMutableTreeNode): List<DefaultMutableTreeNode> {
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

    /**
     * 深度复制树节点及其所有子节点
     * 
     * @param source 源节点
     * @return 复制后的节点
     */
    fun copyNode(source: DefaultMutableTreeNode): DefaultMutableTreeNode {
        val copy = DefaultMutableTreeNode(source.userObject)
        val children = nodeChildren(source)
        children.forEach { child ->
            copy.add(copyNode(child))
        }
        return copy
    }

    /**
     * 检查节点是否包含占位符子节点（用于懒加载）
     * 
     * @param node 要检查的节点
     * @return 如果节点只有一个子节点且该子节点是占位符，返回 true
     */
    fun hasPlaceholder(node: DefaultMutableTreeNode): Boolean {
        if (node.childCount != 1) return false
        val child = node.getChildAt(0) as? DefaultMutableTreeNode ?: return false
        return child.userObject == PLACEHOLDER_LABEL
    }

    /**
     * 创建占位符节点（用于懒加载）
     * 
     * @return 占位符节点
     */
    fun createPlaceholderNode(): DefaultMutableTreeNode {
        return DefaultMutableTreeNode(PLACEHOLDER_LABEL)
    }

    fun pathForDisclosureClick(tree: JTree, x: Int, y: Int): TreePath? {
        val row = tree.getClosestRowForLocation(x, y)
        if (row < 0) return null
        val path = tree.getPathForRow(row) ?: return null
        val bounds = tree.getPathBounds(path) ?: return null
        val insideRow = y >= bounds.y && y < bounds.y + bounds.height
        if (!insideRow) return null
        return if (x < bounds.x) path else null
    }

    fun pathForNodeIconClick(tree: JTree, x: Int, y: Int): TreePath? {
        val path = tree.getPathForLocation(x, y) ?: return null
        val bounds = tree.getPathBounds(path) ?: return null
        val iconRight = bounds.x + 24
        return if (x in bounds.x until iconRight) path else null
    }

    fun collapseVisibleRows(tree: JTree) {
        val paths = mutableListOf<TreePath>()
        for (row in 0 until tree.rowCount) {
            tree.getPathForRow(row)?.let { path ->
                if (tree.isExpanded(path)) {
                    paths.add(path)
                }
            }
        }
        paths.asReversed().forEach { tree.collapsePath(it) }
    }

    fun collapseSelectedPath(tree: JTree) {
        val path = tree.selectionPath ?: return
        if (tree.isExpanded(path)) {
            tree.collapsePath(path)
        }
        tree.selectionPath = path
    }

    fun collapseForNavigation(tree: JTree) {
        val path = tree.selectionPath ?: return
        val selected = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val domainNode = getBookmarkNode(selected) ?: return

        when (domainNode) {
            is BookmarkNode.Group -> {
                if (tree.isExpanded(path)) {
                    collapsePathKeepingSelection(tree, path)
                } else {
                    collapseParentGroupContainer(tree, path)
                }
            }
            is BookmarkNode.Process -> {
                if (tree.isExpanded(path) && hasNavigableChildren(selected)) {
                    collapsePathKeepingSelection(tree, path)
                } else {
                    collapseParentGroupContainer(tree, path)
                }
            }
            is BookmarkNode.Bookmark, is BookmarkNode.DescriptiveBookmark ->
                collapseParentGroupContainer(tree, path)
        }
    }

    /** 先选中目标行再折叠，避免 JTree 在子节点被隐藏时自动改选其它可见行。 */
    private fun collapsePathKeepingSelection(tree: JTree, path: TreePath) {
        tree.selectionPath = path
        if (tree.isExpanded(path)) {
            tree.collapsePath(path)
        }
        tree.selectionPath = path
    }

    /** 折叠并选中最近的父级 Group/Process 容器（书签、已折叠组）。 */
    private fun collapseParentGroupContainer(tree: JTree, childPath: TreePath) {
        val parentPath = domainContainerParentPath(tree, childPath) ?: return
        tree.selectionPath = parentPath
        if (tree.isExpanded(parentPath)) {
            tree.collapsePath(parentPath)
        }
        tree.selectionPath = parentPath
    }

    private fun domainContainerParentPath(tree: JTree, path: TreePath): TreePath? {
        var current = path.parentPath ?: return null
        while (true) {
            val treeNode = current.lastPathComponent as? DefaultMutableTreeNode ?: return null
            when (getBookmarkNode(treeNode)) {
                is BookmarkNode.Group, is BookmarkNode.Process -> return current
                else -> Unit
            }
            val parent = current.parentPath ?: return null
            if (!tree.isRootVisible && parent.parentPath == null) return null
            current = parent
        }
    }

    fun expandSelectedPath(tree: JTree) {
        val path = tree.selectionPath ?: return
        if (!tree.isExpanded(path)) {
            tree.expandPath(path)
        }
        tree.selectionPath = path
    }

    fun expandForNavigation(
        tree: JTree,
        populateChildren: (DefaultMutableTreeNode, BookmarkNode) -> Unit
    ) {
        val path = tree.selectionPath ?: return
        val selected = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val domainNode = getBookmarkNode(selected)
        if (domainNode == null) {
            expandSelectedPath(tree)
            return
        }
        if (!hasNavigableChildren(selected)) return
        materializeIfNeeded(selected, domainNode, populateChildren)
        val children = realChildNodes(selected)
        if (children.isEmpty()) return
        if (!tree.isExpanded(path)) {
            tree.expandPath(path)
        }
        selectPath(tree, TreePath(children.first().path))
    }

    fun moveSelectionByVisibleRow(tree: JTree, delta: Int, searchRelevantIds: Set<String>? = null) {
        if (tree.rowCount <= 0) return
        val currentRow = resolveCurrentVisibleRow(tree) ?: return
        val step = if (delta > 0) 1 else -1
        var row = currentRow + step
        while (row in 0 until tree.rowCount) {
            val path = tree.getPathForRow(row) ?: run { row += step; continue }
            if (isVerticalNavigationRow(tree, path, searchRelevantIds)) {
                selectPathForVerticalNavigation(tree, path)
                return
            }
            row += step
        }
    }

    private fun resolveCurrentVisibleRow(tree: JTree): Int? {
        tree.selectionPath?.let { tree.getRowForPath(it) }?.takeIf { it >= 0 }?.let { return it }
        val lead = tree.leadSelectionRow
        if (lead >= 0) return lead
        val min = tree.minSelectionRow
        if (min >= 0) return min
        return null
    }

    /**
     * 垂直导航：沿可见行步进一格；非搜索任意域节点，搜索期仅 [searchRelevantIds]。
     * 单路径 [moveSelectionByVisibleRow]（与搜索清空后一致），避免 find + fallback 双步进。
     */
    fun moveSelectionConsideringLazyLoad(tree: JTree, delta: Int, searchRelevantIds: Set<String>? = null) {
        if (tree.rowCount <= 0) return
        if (searchRelevantIds?.isEmpty() == true) return
        moveSelectionByVisibleRow(tree, delta, searchRelevantIds)
    }

    /** 垂直导航专用：不调用 [JTree.scrollPathToVisible]（其 [JTree.makeVisible] 会 expandPath 选中节点）。 */
    fun selectPathForVerticalNavigation(tree: JTree, path: TreePath) {
        tree.selectionPath = path
        tree.getPathBounds(path)?.let { tree.scrollRectToVisible(it) }
    }

    private fun hasNavigableChildren(node: DefaultMutableTreeNode): Boolean {
        return hasPlaceholder(node) || realChildNodes(node).isNotEmpty()
    }

    private fun materializeIfNeeded(
        treeNode: DefaultMutableTreeNode,
        domainNode: BookmarkNode,
        populateChildren: (DefaultMutableTreeNode, BookmarkNode) -> Unit
    ) {
        if (hasPlaceholder(treeNode)) {
            populateChildren(treeNode, domainNode)
        }
    }

    private fun realChildNodes(node: DefaultMutableTreeNode): List<DefaultMutableTreeNode> {
        return nodeChildren(node).filter { it.userObject != PLACEHOLDER_LABEL }
    }

    /**
     * ↑↓ 可选中域节点；搜索期另要求 nodeId ∈ searchRelevantIds（索引 [visibleNodeIds]：直接匹配、匹配项祖先、匹配子树）。
     */
    fun isVerticalNavigationRow(tree: JTree, path: TreePath, searchRelevantIds: Set<String>? = null): Boolean {
        val treeNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return false
        val domainNode = getBookmarkNode(treeNode) ?: return false
        val isDomainRow = domainNode is BookmarkNode.Group ||
            domainNode is BookmarkNode.Process ||
            domainNode is BookmarkNode.Bookmark ||
            domainNode is BookmarkNode.DescriptiveBookmark
        if (!isDomainRow) return false
        if (searchRelevantIds == null) return true
        return domainNode.uuid in searchRelevantIds
    }

    fun isVerticalNavigationRow(path: TreePath, searchRelevantIds: Set<String>? = null): Boolean {
        val treeNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return false
        val domainNode = getBookmarkNode(treeNode) ?: return false
        val isDomainRow = domainNode is BookmarkNode.Group ||
            domainNode is BookmarkNode.Process ||
            domainNode is BookmarkNode.Bookmark ||
            domainNode is BookmarkNode.DescriptiveBookmark
        if (!isDomainRow) return false
        if (searchRelevantIds == null) return true
        return domainNode.uuid in searchRelevantIds
    }

    fun moveSelectionDuringSearch(
        tree: JTree,
        delta: Int,
        directMatchIds: Set<String>,
        selectNodeById: (String) -> Boolean
    ) {
        if (directMatchIds.isEmpty()) {
            moveSelectionByVisibleRow(tree, delta)
            return
        }
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        val orderedMatches = collectMatchIdsInPreorder(root, directMatchIds)
        if (orderedMatches.isEmpty()) return
        val currentId = tree.selectionPath?.let { nodeIdAt(it) }
        val currentIndex = currentId?.let { orderedMatches.indexOf(it) } ?: -1
        val nextIndex = when {
            currentIndex < 0 && delta > 0 -> 0
            currentIndex < 0 && delta < 0 -> orderedMatches.lastIndex
            currentIndex >= 0 -> (currentIndex + delta).coerceIn(0, orderedMatches.lastIndex)
            else -> return
        }
        if (currentIndex == nextIndex && currentIndex >= 0) return
        selectNodeById(orderedMatches[nextIndex])
    }

    fun collectMatchIdsInPreorder(root: DefaultMutableTreeNode, matchIds: Set<String>): List<String> {
        val matches = mutableListOf<String>()
        collectMatchIdsInPreorderInternal(root, matchIds, matches)
        return matches
    }

    private fun collectMatchIdsInPreorderInternal(
        node: DefaultMutableTreeNode,
        matchIds: Set<String>,
        out: MutableList<String>
    ) {
        val id = getNodeView(node)?.node?.uuid
        if (id != null && id in matchIds) {
            out.add(id)
        }
        nodeChildren(node).forEach { collectMatchIdsInPreorderInternal(it, matchIds, out) }
    }

    private fun selectPath(tree: JTree, path: TreePath) {
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun nodeIdAt(path: TreePath): String? {
        val treeNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return getNodeView(treeNode)?.node?.uuid
    }

    fun togglePathExpansion(tree: JTree, path: TreePath) {
        if (tree.isExpanded(path)) {
            tree.collapsePath(path)
        } else {
            tree.expandPath(path)
        }
        tree.selectionPath = path
    }

    private fun visibleParentPath(tree: JTree, path: TreePath): TreePath? {
        val parentPath = path.parentPath ?: return null
        if (tree.isRootVisible) return parentPath
        return if (parentPath.parentPath == null) null else parentPath
    }

    /**
     * 从树节点获取 BookmarkNode
     * 
     * @param treeNode 树节点
     * @return BookmarkNode，如果节点不包含 NodeView 则返回 null
     */
    fun getBookmarkNode(treeNode: DefaultMutableTreeNode): BookmarkNode? {
        val view = treeNode.userObject as? NodeView
        return view?.node
    }

    /**
     * 从树节点获取 NodeView
     * 
     * @param treeNode 树节点
     * @return NodeView，如果节点不包含 NodeView 则返回 null
     */
    fun getNodeView(treeNode: DefaultMutableTreeNode): NodeView? {
        return treeNode.userObject as? NodeView
    }

    /**
     * 获取节点的完整路径标签
     * 
     * @param node 树节点
     * @return 路径标签，如果节点不包含 NodeView 则返回空字符串
     */
    fun getPathLabel(node: DefaultMutableTreeNode): String {
        val view = getNodeView(node)
        return view?.pathLabel ?: ""
    }

    /**
     * 展开所有节点
     * 
     * @param tree 树组件
     * @param root 根节点（可选，如果不提供则使用树模型的根节点）
     */
    fun expandAll(tree: Tree, root: DefaultMutableTreeNode? = null) {
        val startNode = root ?: (tree.model.root as? DefaultMutableTreeNode) ?: return
        expandNodeRecursive(tree, startNode)
    }

    /**
     * 折叠所有节点
     * 
     * @param tree 树组件
     * @param root 根节点（可选，如果不提供则使用树模型的根节点）
     */
    fun collapseAll(tree: Tree, root: DefaultMutableTreeNode? = null) {
        val startNode = root ?: (tree.model.root as? DefaultMutableTreeNode) ?: return
        collapseNodeRecursive(tree, startNode)
    }

    /**
     * 递归展开节点及其所有子节点
     */
    private fun expandNodeRecursive(tree: Tree, node: DefaultMutableTreeNode) {
        val path = TreePath(node.path)
        tree.expandPath(path)
        nodeChildren(node).forEach { child ->
            expandNodeRecursive(tree, child)
        }
    }

    /**
     * 递归折叠节点及其所有子节点
     */
    private fun collapseNodeRecursive(tree: Tree, node: DefaultMutableTreeNode) {
        nodeChildren(node).forEach { child ->
            collapseNodeRecursive(tree, child)
        }
        val path = TreePath(node.path)
        tree.collapsePath(path)
    }
}
