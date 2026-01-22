package emohce.presentation.editor.inlay

import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ui.FormBuilder
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import kotlinx.coroutines.runBlocking
import javax.swing.JCheckBox
import javax.swing.JComponent

data class LineEndSettings(
    var enabled: Boolean = true,
    var onlyCurrentLine: Boolean = false,
    var showBookmarks: Boolean = true,
    var showProcesses: Boolean = true
)

@Suppress("UnstableApiUsage")
class BookmarkLineEndInlayProvider : InlayHintsProvider<LineEndSettings> {
    override val key: SettingsKey<LineEndSettings> = SettingsKey("CodeRemarkTour.LineEnd")
    override val name: String = "CodeRemarkTour Line End"
    override val previewText: String = "fun demo() {}"

    override fun createSettings(): LineEndSettings = LineEndSettings()

    override fun createConfigurable(settings: LineEndSettings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override val mainCheckboxText: String = "Enable line end hints"

            override fun createComponent(listener: ChangeListener): JComponent {
                val enabled = JCheckBox("Enable line end hints", settings.enabled)
                val onlyCurrentLine = JCheckBox("Only current caret line", settings.onlyCurrentLine)
                val showBookmarks = JCheckBox("Show bookmarks", settings.showBookmarks)
                val showProcesses = JCheckBox("Show processes", settings.showProcesses)

                enabled.addActionListener { settings.enabled = enabled.isSelected; listener.settingsChanged() }
                onlyCurrentLine.addActionListener { settings.onlyCurrentLine = onlyCurrentLine.isSelected; listener.settingsChanged() }
                showBookmarks.addActionListener { settings.showBookmarks = showBookmarks.isSelected; listener.settingsChanged() }
                showProcesses.addActionListener { settings.showProcesses = showProcesses.isSelected; listener.settingsChanged() }

                return FormBuilder.createFormBuilder()
                    .addComponent(enabled)
                    .addComponent(onlyCurrentLine)
                    .addComponent(showBookmarks)
                    .addComponent(showProcesses)
                    .panel
            }
        }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: LineEndSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector {
        return object : FactoryInlayHintsCollector(editor) {
            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                if (element !is PsiFile) return true
                if (!settings.enabled) return false
                val virtualFile = element.virtualFile ?: return false
                val document = PsiDocumentManager.getInstance(element.project).getDocument(element)
                    ?: return false
                val hints = runBlocking {
                    val locator = ServiceLocator(element.project)
                    val root = locator.bookmarkRepository.getRootNode()
                    collectHints(root, virtualFile.path)
                }
                if (hints.isEmpty()) return false
                val caretLine = editor.caretModel.primaryCaret.logicalPosition.line
                val byLine = hints.groupBy { it.line }
                byLine.forEach { (line, entries) ->
                    if (line < 0 || line >= document.lineCount) return@forEach
                    if (settings.onlyCurrentLine && line != caretLine) return@forEach
                    val filtered = entries.filter {
                        (it.type == HintType.BOOKMARK && settings.showBookmarks) ||
                            (it.type == HintType.PROCESS && settings.showProcesses)
                    }
                    if (filtered.isEmpty()) return@forEach
                    val offset = document.getLineEndOffset(line)
                    val label = filtered.joinToString(" | ") { it.label }
                    val presentation = factory.smallText(" $label")
                    sink.addInlineElement(offset, true, presentation, false)
                }
                return false
            }
        }
    }

    private fun collectHints(root: BookmarkNode, filePath: String): List<HintEntry> {
        val hints = mutableListOf<HintEntry>()
        traverse(root) { node ->
            when (node) {
                is BookmarkNode.Bookmark -> {
                    if (node.filePath == filePath) {
                        val name = node.name.ifBlank { "Bookmark" }
                        hints.add(HintEntry(node.line, "[B] $name", HintType.BOOKMARK))
                    }
                }
                is BookmarkNode.Process -> {
                    val entryLine = node.entryLine
                    if (node.entryFilePath == filePath && entryLine != null) {
                        val name = node.name.ifBlank { "Process" }
                        hints.add(HintEntry(entryLine, "[P] $name", HintType.PROCESS))
                    }
                }
                else -> Unit
            }
        }
        return hints
    }

    private fun traverse(node: BookmarkNode, visitor: (BookmarkNode) -> Unit) {
        visitor(node)
        when (node) {
            is BookmarkNode.Group -> node.children.forEach { traverse(it, visitor) }
            is BookmarkNode.Process -> node.steps.forEach { traverse(it, visitor) }
            else -> Unit
        }
    }

    private data class HintEntry(val line: Int, val label: String, val type: HintType)

    private enum class HintType { BOOKMARK, PROCESS }
}
