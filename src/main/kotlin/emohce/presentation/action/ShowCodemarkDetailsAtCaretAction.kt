package emohce.presentation.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import emohce.presentation.index.BookmarkIndexService
import emohce.presentation.ui.ChipLabel
import emohce.presentation.ui.DetailsPopupPanel
import emohce.presentation.ui.RoundedPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.event.HyperlinkEvent

class ShowCodemarkDetailsAtCaretAction : AnAction() {
    private val logger = Logger.getInstance(ShowCodemarkDetailsAtCaretAction::class.java)
    private var currentPopup: JBPopup? = null

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return logger.warn("[ACTION_SHOW_DETAILS_AT_CARET] No project")
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return logger.warn("[ACTION_SHOW_DETAILS_AT_CARET] No editor")
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return logger.warn("[ACTION_SHOW_DETAILS_AT_CARET] No file")

        val caret = editor.caretModel.primaryCaret
        val line = caret.logicalPosition.line

        val indexService = BookmarkIndexService.getInstance(project)
        val entry = indexService.entriesForFile(file.path).firstOrNull { it.line == line }
            ?: return logger.debug("[ACTION_SHOW_DETAILS_AT_CARET] No codemark at line $line")

        logger.debug("[ACTION_SHOW_DETAILS_AT_CARET] Found codemark ${entry.nodeId} at line $line")

        // Close existing popup
        currentPopup?.cancel()
        currentPopup = null

        // Load node data
        val locator = ServiceLocator.get(project)
        val node = runBlocking {
            withContext(Dispatchers.IO) {
                locator.bookmarkRepository.findByUuid(entry.nodeId)
            }
        } ?: return logger.warn("[ACTION_SHOW_DETAILS_AT_CARET] Node not found")

        // Create popup content with tree-view style
        val content = createStyledDetailsContent(node, file.path, line)
        
        // Create popup
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, content)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(false)
            .createPopup()

        // Add ESC key listener
        content.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escape")
        content.actionMap.put("escape", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                popup.cancel()
            }
        })

        popup.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
            override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                if (currentPopup === popup) currentPopup = null
            }
        })

        currentPopup = popup
        
        // Show popup at editor caret position
        val visualPosition = editor.caretModel.primaryCaret.visualPosition
        val xyPoint = editor.visualPositionToXY(visualPosition)
        val screenPoint = editor.component.locationOnScreen
        val showPoint = java.awt.Point(screenPoint.x + xyPoint.x, screenPoint.y + xyPoint.y + 20)
        popup.show(RelativePoint.fromScreen(showPoint))
    }

    private fun createStyledDetailsContent(node: BookmarkNode, filePath: String, line: Int): JComponent {
        val accentColor = getAccentColor(node)
        val displayName = node.name
        val description = when (node) {
            is BookmarkNode.Bookmark -> node.description
            is BookmarkNode.Group -> node.description
            is BookmarkNode.Process -> node.description
            is BookmarkNode.DescriptiveBookmark -> node.description
        }

        val titleLabel = JLabel(displayName).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 3f)
            foreground = JBColor.foreground()
        }
        
        val typeChip = ChipLabel(getNodeKindLabel(node), accentColor)
        val locationChip = ChipLabel("$filePath:$line", JBColor(0x596579, 0xAEB6C4), filled = false)
        
        val metaRow = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(typeChip)
            add(Box.createHorizontalStrut(6))
            add(locationChip)
        }
        
        val topRow = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            add(metaRow, BorderLayout.WEST)
        }
        
        val titleRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(9)
            add(titleLabel, BorderLayout.WEST)
        }
        
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(16, 18, 12, 18)
            add(topRow, BorderLayout.NORTH)
            add(titleRow, BorderLayout.SOUTH)
        }

        val descriptionPane = javax.swing.JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty(10, 12)
            text = if (description.isBlank()) {
                """<p class="empty">No description</p>"""
            } else {
                renderMarkdown(description)
            }
            addHyperlinkListener { event ->
                if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    handleLink(event)
                }
            }
        }
        
        val sectionLabel = JLabel("DESCRIPTION").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.BOLD, font.size2D - 1f)
            border = JBUI.Borders.empty(0, 0, 7, 0)
        }
        
        val body = JScrollPane(descriptionPane).apply {
            border = BorderFactory.createLineBorder(JBColor.border())
            preferredSize = Dimension(500, 310)
        }
        
        val bodyWrap = RoundedPanel(JBColor(0xFFFFFF, 0x24272D), JBColor.border(), 10).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(11, 12, 12, 12)
            add(sectionLabel, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
        
        val content = DetailsPopupPanel(accentColor).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(0, 0, 14, 0)
            add(header, BorderLayout.NORTH)
            add(bodyWrap, BorderLayout.CENTER)
        }

        return content
    }

    private fun getAccentColor(node: BookmarkNode): Color {
        return when (node) {
            is BookmarkNode.Bookmark -> JBColor(0x4A90E2, 0x5C8FD1)
            is BookmarkNode.Group -> JBColor(0xF5A623, 0xE89613)
            is BookmarkNode.Process -> JBColor(0x7ED321, 0x6BC211)
            is BookmarkNode.DescriptiveBookmark -> JBColor(0x9013FE, 0x7B0DE8)
        }
    }

    private fun getNodeKindLabel(node: BookmarkNode): String {
        return when (node) {
            is BookmarkNode.Bookmark -> "BOOKMARK"
            is BookmarkNode.Group -> "GROUP"
            is BookmarkNode.Process -> "PROCESS"
            is BookmarkNode.DescriptiveBookmark -> "NOTE"
        }
    }

    private fun renderMarkdown(text: String): String {
        var html = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        html = html.replace("\n", "<br>")
        html = Regex("""`([^`]+)`""").replace(html) { "<code>${it.groupValues[1]}</code>" }
        html = Regex("""\*\*([^*]+)\*\*""").replace(html) { "<b>${it.groupValues[1]}</b>" }
        html = Regex("""\*([^*]+)\*""").replace(html) { "<i>${it.groupValues[1]}</i>" }
        return html
    }

    private fun handleLink(event: HyperlinkEvent) {
        event.url?.let { url ->
            if (url.protocol == "http" || url.protocol == "https") {
                com.intellij.ide.BrowserUtil.browse(url)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val hasEditor = editor != null
        val hasFile = file != null

        e.presentation.isEnabledAndVisible = hasEditor && hasFile && project != null

        if (hasEditor && hasFile && project != null) {
            val indexService = BookmarkIndexService.getInstance(project)
            val caretLine = editor.caretModel.primaryCaret.logicalPosition.line
            val hasCodemark = indexService.entriesForFile(file.path).any { it.line == caretLine }
            e.presentation.isEnabled = hasCodemark
        }
    }
}
