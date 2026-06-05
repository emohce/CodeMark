package emohce.presentation.toolwindow.panel.render

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.speedSearch.SpeedSearchUtil
import emohce.presentation.toolwindow.panel.BookmarkPanel
import java.awt.Color
import javax.swing.JTree

class BookmarkTreeCellRenderer : ColoredTreeCellRenderer() {
    var highlightNodeId: String? = null
    var speedSearchHighlightEnabled: Boolean = false

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
            val baseAttrs = SimpleTextAttributes.REGULAR_ATTRIBUTES
            val highlightAttrs = if (nodeView.node.uuid == highlightNodeId) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Color(0, 153, 0))
            } else null
            val nameAttrs = highlightAttrs ?: baseAttrs
            append(nodeView.displayName, nameAttrs)
            val suffix = nodeView.suffix
            if (suffix.isNotBlank()) {
                append(" $suffix", SimpleTextAttributes.GRAY_ATTRIBUTES)
            }
            // Render broken bookmark description
            if (nodeView.isFileBroken && nodeView.node.description.isNotBlank()) {
                append(" - ${nodeView.node.description.trim().replace("\n", " ")}", SimpleTextAttributes.GRAY_ATTRIBUTES)
            }
            toolTipText = nodeView.tooltip
            SpeedSearchUtil.applySpeedSearchHighlighting(tree, this, speedSearchHighlightEnabled, selected)
        } else if (nodeView is String) {
            append(nodeView, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            toolTipText = null
        }
    }
}
