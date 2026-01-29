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
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ui.FormBuilder
import emohce.presentation.editor.inlay.BookmarkInlayRenderer
import emohce.presentation.toolwindow.BookmarkViewModel
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import emohce.presentation.index.BookmarkIndexService
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

enum class HintType {
    BOOKMARK, PROCESS
}

data class HintEntry(
    val line: Int,
    val label: String,
    val id: String,
    val type: HintType
)

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
                // Since we don't show in settings, return empty panel
                return JPanel()
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
                val index = BookmarkIndexService.getInstance(file.project)
                val indexed = index.entriesForFile(normalizedPath).map {
                    val label = it.label.ifBlank {
                        if (it.type == BookmarkIndexService.NodeType.PROCESS) "Process" else "Bookmark"
                    }
                    val hintType = if (it.type == BookmarkIndexService.NodeType.PROCESS) HintType.PROCESS else HintType.BOOKMARK
                    HintEntry(it.line, label, it.nodeId, hintType)
                }
                if (indexed.isNotEmpty()) return@withContext indexed

                // Fallback to repository traversal if index empty
                val locator = ServiceLocator(file.project)
                val root = locator.bookmarkRepository.getRootNode()
                collectHints(root, normalizedPath)
            }
        }
        if (hints.isEmpty()) return null

        val viewModel = ServiceLocator(file.project).bookmarkViewModel

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
                    if (filtered.isEmpty()) return@forEach
                    val distinct = filtered.distinctBy { it.id }
                    val label = if (distinct.size == 1) {
                        distinct.first().label.ifBlank { "Bookmark" }
                    } else {
                        distinct.size.toString()
                    }
                    val offset = document.getLineEndOffset(line)
                    val presentation = createBadgePresentation(factory, label, distinct.first().id, normalizedPath, line, file.project, editor, viewModel)
                    sink.addInlineElement(offset, true, presentation, false)
                }
                return false
            }
        }
    }

    private fun createBadgePresentation(
        factory: PresentationFactory,
        textValue: String,
        nodeId: String,
        filePath: String,
        line: Int,
        project: Project,
        editor: Editor,
        viewModel: BookmarkViewModel
    ): InlayPresentation {
        // Use custom renderer that draws icon and text
        // Gutter icon is displayed via BookmarkHighlighterService (RangeHighlighter)
        // Here we create a presentation with icon (blue info icon) and text using remarkInlay style
        val renderer = BookmarkInlayRenderer(textValue, nodeId, filePath, line, project, editor, viewModel)
        return renderer
    }

    private fun collectHints(root: BookmarkNode, filePath: String): List<HintEntry> {
        val hints = mutableListOf<HintEntry>()
        val normalizedTarget = FileUtil.toSystemIndependentName(filePath)

        traverse(root) { node ->
            when (node) {
                is BookmarkNode.Bookmark -> {
                    if (FileUtil.toSystemIndependentName(node.filePath) == normalizedTarget) {
                        val label = node.name.ifBlank { "Bookmark" }
                        hints.add(HintEntry(node.line, label, node.uuid, HintType.BOOKMARK))
                    }
                }
                is BookmarkNode.Process -> {
                    val entryLine = node.entryLine
                    if (node.entryFilePath != null &&
                        FileUtil.toSystemIndependentName(node.entryFilePath) == normalizedTarget &&
                        entryLine != null
                    ) {
                        val label = node.name.ifBlank { "Process" }
                        hints.add(HintEntry(entryLine, label, node.uuid, HintType.PROCESS))
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
}
