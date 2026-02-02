package emohce.presentation.editor.linepainter

import com.intellij.openapi.editor.EditorLinePainter
import com.intellij.openapi.editor.LineExtensionInfo
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import emohce.presentation.index.BookmarkIndexService
import java.awt.Font

/**
 * Line-end painter for codemarks: shows " // 📌 label" at line end for any file.
 * Color and emoji are controlled here (TextAttributes foreground, prefix in text).
 */
class BookmarkLineEndPainter : EditorLinePainter() {

    /** Foreground color for line-end hint text. Change this to customize (e.g. JBColor.GRAY, JBColor.BLUE). */
    private val lineEndHintColor = JBColor.GRAY

    /** Emoji shown before the label; matches gutter icon (bookmark). Use "" to disable, or e.g. "🔖 " "📌 ". */
    private val lineEndHintEmoji = " 📌"

    override fun getLineExtensions(
        project: Project,
        virtualFile: VirtualFile,
        lineIndex: Int
    ): Collection<LineExtensionInfo>? {
        val path = FileUtil.toSystemIndependentName(virtualFile.path)
        val index = BookmarkIndexService.getInstance(project)
        val entries = index.entriesForFile(path).filter { it.line == lineIndex }
        if (entries.isEmpty()) return null
        val distinct = entries.distinctBy { it.nodeId }
        val label = when {
            distinct.size == 1 -> {
                val first = distinct.first()
                first.label.ifBlank {
                    if (first.type == BookmarkIndexService.NodeType.PROCESS) "Process" else "Bookmark"
                }
            }
            else -> distinct.size.toString()
        }
        val text = "$lineEndHintEmoji$label"
        val attrs = TextAttributes(lineEndHintColor, null, null, null, Font.PLAIN)
        return listOf(LineExtensionInfo(text, attrs))
    }
}
