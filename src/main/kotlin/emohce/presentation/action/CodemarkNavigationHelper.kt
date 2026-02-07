package emohce.presentation.action

import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import emohce.presentation.editor.highlighter.BookmarkHighlighterService
import java.io.File
import javax.swing.SwingUtilities

/**
 * Resolves path (absolute or project-relative), opens file, moves caret to line/column and scrolls.
 * Used by Next/Prev Codemark actions so navigation works even when the tool window was never opened.
 */
object CodemarkNavigationHelper {

    fun navigateToEntry(project: Project, filePath: String, line: Int, column: Int) {
        val normalized = FileUtil.toSystemIndependentName(filePath)
        var file: VirtualFile? = LocalFileSystem.getInstance().findFileByPath(normalized)
        if (file == null && project.basePath != null) {
            val pathFile = File(normalized)
            val absolutePath = if (pathFile.isAbsolute) normalized else FileUtil.toSystemIndependentName(File(project.basePath, normalized).canonicalPath)
            file = LocalFileSystem.getInstance().findFileByPath(absolutePath)
        }
        if (file == null) return
        val fem = FileEditorManager.getInstance(project)
        fem.openFile(file, true)
        SwingUtilities.invokeLater {
            val targetLine = line.coerceAtLeast(0)
            fem.getEditors(file).forEach { editor ->
                (editor as? TextEditor)?.editor?.let { textEditor ->
                    val document = textEditor.document
                    if (targetLine < document.lineCount) {
                        val lineStart = document.getLineStartOffset(targetLine)
                        val lineEnd = document.getLineEndOffset(targetLine)
                        val col = column.coerceAtLeast(0).coerceAtMost((lineEnd - lineStart).coerceAtLeast(0))
                        val targetOffset = lineStart + col
                        textEditor.caretModel.primaryCaret.moveToOffset(targetOffset)
                        textEditor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                    }
                }
            }
            BookmarkHighlighterService.getInstance(project).flashLineForFile(file.path, targetLine)
        }
    }
}
