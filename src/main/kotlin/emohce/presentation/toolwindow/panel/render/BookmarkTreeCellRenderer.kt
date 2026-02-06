package emohce.presentation.toolwindow.panel.render

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import emohce.presentation.toolwindow.panel.BookmarkPanel
import java.awt.Color
import javax.swing.JTree

class BookmarkTreeCellRenderer : ColoredTreeCellRenderer() {
    var highlightNodeId: String? = null

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val nodeView = (value as? javax.swing.tree.DefaultMutableTreeNode)?.userObject
        if (nodeView is BookmarkPanel.NodeView) {
            icon = NodeIcons.iconFor(nodeView.node, nodeView.isReferencedTarget)
            val baseAttrs = if (nodeView.isSearchResult) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
            val highlightAttrs = if (nodeView.node.uuid == highlightNodeId) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Color(0, 153, 0))
            } else null
            val nameAttrs = highlightAttrs ?: baseAttrs
            append(nodeView.displayName, nameAttrs)
            val suffix = nodeView.suffix
            if (suffix.isNotBlank()) {
                append(" $suffix", SimpleTextAttributes.GRAY_ATTRIBUTES)
            }
            toolTipText = nodeView.tooltip
        } else if (nodeView is String) {
            append(nodeView, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            toolTipText = null
        }
    }
}
