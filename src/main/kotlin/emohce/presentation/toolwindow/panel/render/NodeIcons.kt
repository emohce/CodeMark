package emohce.presentation.toolwindow.panel.render

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import emohce.domain.model.BookmarkNode
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.ImageIcon

object NodeIcons {
    /** Plugin icon scaled to 16x16 for tree and gutter. Raw PNG should be 40x40 for tool window. */
    val bookmark: Icon = scaleIcon(
        IconLoader.getIcon("/META-INF/pluginIcon.png", NodeIcons::class.java),
        16, 16
    )

    private fun scaleIcon(icon: Icon, w: Int, h: Int): Icon {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        (out.createGraphics() as Graphics2D).apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            scale(w.toDouble() / icon.iconWidth, h.toDouble() / icon.iconHeight)
            icon.paintIcon(null, this, 0, 0)
            dispose()
        }
        return ImageIcon(out)
    }
    val group: Icon = AllIcons.Nodes.Folder
    val process: Icon = AllIcons.Actions.Execute
    val note: Icon = AllIcons.Actions.Edit
    val reference: Icon = AllIcons.Actions.Find

    fun iconFor(node: BookmarkNode, isReferencedTarget: Boolean): Icon {
        return when (node) {
            is BookmarkNode.Bookmark -> if (isReferencedTarget) reference else bookmark
            is BookmarkNode.Group -> group
            is BookmarkNode.Process -> process
            is BookmarkNode.DescriptiveBookmark -> note
        }
    }
}
