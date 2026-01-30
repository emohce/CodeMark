package emohce.presentation.editor.inlay

import com.intellij.codeInsight.hints.presentation.*
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.impl.FontInfo
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.UIUtil
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import emohce.presentation.toolwindow.BookmarkEditDialogUtil
import emohce.presentation.toolwindow.BookmarkIntent
import emohce.presentation.toolwindow.BookmarkViewModel
import kotlinx.coroutines.runBlocking
import java.awt.*
import java.awt.event.MouseEvent
import javax.swing.Icon

class BookmarkInlayRenderer(
    private val label: String,
    private val nodeId: String,
    private val filePath: String,
    private val line: Int,
    private val project: Project,
    private val editor: Editor,
    private val viewModel: BookmarkViewModel
) : InlayPresentation {

    private companion object {
        const val ICON_TEXT_GAP = 2
    }

    // Gutter icon is displayed via BookmarkHighlighterService (RangeHighlighter)
    // Here we also display a small blue info icon at line end, followed by text with remarkInlay style
    private val icon = AllIcons.General.Information // Blue info icon
    private val text = "$label "

    private var isHovered = false
    private var textStartX = 0

    override val width: Int
        get() {
            val font = getFont()
            val metrics = getFontMetrics(font)
            return icon.iconWidth + ICON_TEXT_GAP + metrics.stringWidth(text)
        }

    override val height: Int
        get() = maxOf(icon.iconHeight, getFontMetrics(getFont()).height)

    override fun paint(g: Graphics2D, attributes: TextAttributes) {
        if (label.isBlank()) return

        val inlineAttributes = getAttributes()
        if (inlineAttributes?.foregroundColor == null) return

        val font = getFont()
        g.font = font
        val metrics = getFontMetrics(font)

        var curX = 0
        
        // Draw blue info icon
        icon.paintIcon(editor.component, g, curX, getIconY(icon))
        curX += icon.iconWidth + ICON_TEXT_GAP
        
        // Draw text with remarkInlay style
        textStartX = curX
        g.color = inlineAttributes.foregroundColor
        g.drawString(text, curX, getTextY(metrics))
    }
    
    private fun getIconY(icon: Icon): Int {
        return (height - icon.iconHeight) / 2
    }

    override fun mouseClicked(event: MouseEvent, translated: Point) {
        if (!isHovered) return
        
        when {
            event.button == MouseEvent.BUTTON1 -> {
                // Left click: show edit dialog
                showEditPopup()
            }
            event.button == MouseEvent.BUTTON3 || event.isPopupTrigger -> {
                // Right click: show delete confirmation
                showDeleteConfirmation()
            }
        }
    }

    override fun mouseMoved(event: MouseEvent, translated: Point) {
        setHovered(translated.x >= textStartX, event)
    }

    override fun mouseExited() {
        setHovered(false, null)
    }

    private fun setHovered(active: Boolean, event: MouseEvent?) {
        if (active != isHovered) {
            isHovered = active
            editor.component.cursor = if (active) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            fireSizeChanged(Dimension(width, height), Dimension(width, height))
        }
    }

    private fun showEditPopup() {
        runBlocking {
            val locator = ServiceLocator.get(project)
            val node = locator.bookmarkRepository.findByUuid(nodeId) ?: return@runBlocking

            val updated = when (node) {
                is BookmarkNode.Bookmark -> BookmarkEditDialogUtil.editBookmark(project, node)
                is BookmarkNode.DescriptiveBookmark -> BookmarkEditDialogUtil.editDescriptive(project, node)
                is BookmarkNode.Group -> BookmarkEditDialogUtil.editGroup(project, node)
                is BookmarkNode.Process -> BookmarkEditDialogUtil.editProcess(project, node)
            } ?: return@runBlocking

            // 发送编辑意图，ViewModel 会自动刷新树形结构和 JSON
            viewModel.processIntent(BookmarkIntent.EditNode(updated))
        }
    }

    private fun showDeleteConfirmation() {
        runBlocking {
            val locator = ServiceLocator.get(project)
            val node = locator.bookmarkRepository.findByUuid(nodeId) ?: return@runBlocking
            
            if (node.uuid == "root") return@runBlocking

            // Get reference information
            val allReferences = locator.referenceRepository.getAllReferences()
            val outgoing = allReferences.count { it.sourceId == nodeId }
            val incoming = allReferences.count { it.targetId == nodeId }

            // Build warning message
            val warning = when (node) {
                is BookmarkNode.Bookmark -> {
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

            // Show confirmation dialog
            val confirmed = Messages.showYesNoDialog(
                project,
                warning,
                "Delete",
                Messages.getQuestionIcon()
            )

            if (confirmed == Messages.YES) {
                viewModel.processIntent(BookmarkIntent.DeleteNode(nodeId))
            }
        }
    }

    private fun getTextY(metrics: FontMetrics): Int {
        return metrics.ascent
    }

    private fun getFont(): Font {
        val colorsScheme = editor.colorsScheme
        return UIUtil.getFontWithFallback(colorsScheme.editorFontName, Font.PLAIN, colorsScheme.editorFontSize)
    }

    private fun getFontMetrics(font: Font): FontMetrics {
        return FontInfo.getFontMetrics(font, FontInfo.getFontRenderContext(editor.contentComponent))
    }

    private fun getAttributes(): TextAttributes? {
        val colorsScheme = editor.colorsScheme
        // Use remarkInlay style (similar to CodeReadingMarkNotePro)
        return colorsScheme.getAttributes(BookmarkInlayTextAttributes.REMARK_INLAY)
    }

    override fun fireSizeChanged(previous: Dimension, current: Dimension) {
        // Implement if needed
    }

    override fun fireContentChanged(area: Rectangle) {
        // Implement if needed
    }

    override fun addListener(listener: PresentationListener) {
        // Implement if needed
    }

    override fun removeListener(listener: PresentationListener) {
        // Implement if needed
    }

    override fun toString(): String = "BookmarkInlayRenderer(label='$label')"
}
