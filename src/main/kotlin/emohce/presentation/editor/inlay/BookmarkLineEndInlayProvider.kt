package emohce.presentation.editor.inlay

import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.FormBuilder
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    override val isVisibleInSettings: Boolean = false

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
    ): InlayHintsCollector? {
        if (!settings.enabled) return null
        val virtualFile = file.virtualFile ?: return null
        val normalizedPath = FileUtil.toSystemIndependentName(virtualFile.path)
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null

        val hints = runBlocking {
            withContext(Dispatchers.IO) {
                val locator = ServiceLocator(file.project)
                val root = locator.bookmarkRepository.getRootNode()
                collectHints(root, normalizedPath)
            }
        }
        if (hints.isEmpty()) return null

        return object : FactoryInlayHintsCollector(editor) {
            private var collected = false

            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                if (element !is PsiFile) return true
                if (collected) return false
                collected = true

                val caretLine = editor.caretModel.primaryCaret.logicalPosition.line
                val byLine = hints.groupBy { it.line }
                byLine.forEach { (line, entries) ->
                    if (line < 0 || line >= document.lineCount) return@forEach
                    if (settings.onlyCurrentLine && line != caretLine) return@forEach
                    val filtered = entries.filter {
                        (it.type == HintType.BOOKMARK && settings.showBookmarks) ||
                            (it.type == HintType.PROCESS && settings.showProcesses)
                    }
                    val count = filtered.size
                    if (count == 0) return@forEach
                    val offset = document.getLineEndOffset(line)
                    val presentation = createBadgePresentation(factory, count)
                    sink.addInlineElement(offset, true, presentation, false)
                }
                return false
            }
        }
    }

    private fun createBadgePresentation(factory: PresentationFactory, count: Int): InlayPresentation {
        val text = factory.smallText(" $count ")
        val withBackground = factory.roundWithBackground(text)
        return withBackground
    }

    private fun collectHints(root: BookmarkNode, filePath: String): List<HintEntry> {
        val hints = mutableListOf<HintEntry>()
        val normalizedTarget = FileUtil.toSystemIndependentName(filePath)
        traverse(root) { node ->
            when (node) {
                is BookmarkNode.Bookmark -> {
                    if (FileUtil.toSystemIndependentName(node.filePath) == normalizedTarget) {
                        hints.add(HintEntry(node.line, node.uuid, HintType.BOOKMARK))
                    }
                }
                is BookmarkNode.Process -> {
                    val entryLine = node.entryLine
                    if (node.entryFilePath != null &&
                        FileUtil.toSystemIndependentName(node.entryFilePath) == normalizedTarget &&
                        entryLine != null
                    ) {
                        hints.add(HintEntry(entryLine, node.uuid, HintType.PROCESS))
                    }
                }
                else -> Unit
            }
        }
        return hints.distinctBy { it.line to it.id }
    }

    private fun traverse(node: BookmarkNode, visitor: (BookmarkNode) -> Unit) {
        visitor(node)
        when (node) {
            is BookmarkNode.Group -> node.children.forEach { traverse(it, visitor) }
            is BookmarkNode.Process -> node.steps.forEach { traverse(it, visitor) }
            else -> Unit
        }
    }

    private data class HintEntry(val line: Int, val id: String, val type: HintType)

    private enum class HintType { BOOKMARK, PROCESS }
}
