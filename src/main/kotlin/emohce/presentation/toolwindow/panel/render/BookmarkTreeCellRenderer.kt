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

    private fun appendMarkdownText(text: String, baseColor: Color) {
        val plainAttrs = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, baseColor)
        var i = 0
        while (i < text.length) {
            when {
                // Bold: **text**
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        append(text.substring(i + 2, end), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, baseColor))
                        i = end + 2
                    } else {
                        append(text[i].toString(), plainAttrs)
                        i++
                    }
                }
                // Italic: *text*
                text.startsWith("*", i) && (i == 0 || text[i - 1] != '*') -> {
                    val end = text.indexOf("*", i + 1)
                    if (end != -1 && (end + 1 >= text.length || text[end + 1] != '*')) {
                        append(text.substring(i + 1, end), SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, baseColor))
                        i = end + 1
                    } else {
                        append(text[i].toString(), plainAttrs)
                        i++
                    }
                }
                // Code: `text`
                text.startsWith("`", i) -> {
                    val end = text.indexOf("`", i + 1)
                    if (end != -1) {
                        append(text.substring(i + 1, end), SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, baseColor))
                        i = end + 1
                    } else {
                        append(text[i].toString(), plainAttrs)
                        i++
                    }
                }
                else -> {
                    append(text[i].toString(), plainAttrs)
                    i++
                }
            }
        }
    }

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
                appendMarkdownText(" $suffix", Color.GRAY)
            }
            val descriptionPreview = nodeView.descriptionPreview
            if (descriptionPreview.isNotBlank()) {
                append(" $descriptionPreview", SimpleTextAttributes.GRAY_ATTRIBUTES)
            }
            toolTipText = nodeView.tooltip
            SpeedSearchUtil.applySpeedSearchHighlighting(tree, this, speedSearchHighlightEnabled, selected)
        } else if (nodeView is String) {
            append(nodeView, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            toolTipText = null
        }
    }
}
