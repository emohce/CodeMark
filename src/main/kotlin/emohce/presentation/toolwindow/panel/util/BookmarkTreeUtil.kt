package emohce.presentation.toolwindow.panel.util

import com.intellij.ui.treeStructure.Tree
import emohce.domain.model.BookmarkNode
import emohce.presentation.toolwindow.panel.BookmarkPanel.NodeView
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * 书签树操作工具类
 * 
 * 封装树节点的查找、选择、展开等常用操作，提高代码可维护性和复用性。
 */
object BookmarkTreeUtil {
    
    private const val PLACEHOLDER_LABEL = "Loading..."

    /**
     * 根据节点 ID 查找树节点
     * 
     * @param root 根节点
     * @param nodeId 要查找的节点 ID
     * @return 找到的节点，如果不存在则返回 null
     */
    fun findNodeById(root: DefaultMutableTreeNode, nodeId: String): DefaultMutableTreeNode? {
        val view = root.userObject as? NodeView
        if (view?.node?.uuid == nodeId) return root
        
        val children = root.children()
        while (children.hasMoreElements()) {
            val child = children.nextElement() as? DefaultMutableTreeNode ?: continue
            val match = findNodeById(child, nodeId)
            if (match != null) return match
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
        val root = treeModel.root as? DefaultMutableTreeNode ?: return false
        val node = findNodeById(root, nodeId) ?: return false
        
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.expandPath(path)
        tree.scrollPathToVisible(path)
        return true
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
