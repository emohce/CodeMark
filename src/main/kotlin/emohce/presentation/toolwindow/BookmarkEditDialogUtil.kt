package emohce.presentation.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.FormBuilder
import emohce.domain.model.BookmarkNode
import java.io.File
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField

object BookmarkEditDialogUtil {

    fun editBookmark(project: Project, node: BookmarkNode.Bookmark): BookmarkNode.Bookmark? {
        val nameField = JTextField(node.name)
        val descField = JTextField(node.description)
        val pathField = JTextField(node.filePath)
        val lineField = JTextField(node.line.toString())
        val columnField = JTextField(node.column.toString())
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name", nameField)
            .addLabeledComponent("Description", descField)
            .addLabeledComponent("File path", pathField)
            .addLabeledComponent("Line", lineField)
            .addLabeledComponent("Column", columnField)
            .panel

        if (!showPanelOkCancel(panel, "Edit Bookmark")) return null
        val path = pathField.text.trim()
        if (!ensureFileExists(path, "Edit Bookmark")) return null
        val line = (lineField.text.trim().toIntOrNull() ?: node.line).coerceAtLeast(0)
        val column = (columnField.text.trim().toIntOrNull() ?: node.column).coerceAtLeast(0)
        return node.copy(
            name = nameField.text.trim(),
            description = descField.text.trim(),
            filePath = path,
            line = line,
            column = column
        )
    }

    fun editDescriptive(project: Project, node: BookmarkNode.DescriptiveBookmark): BookmarkNode.DescriptiveBookmark? {
        val nameField = JTextField(node.name)
        val descField = JTextField(node.description)
        val markdownField = JTextArea(node.markdownContent, 6, 40)
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name", nameField)
            .addLabeledComponent("Description", descField)
            .addLabeledComponent("Markdown", JScrollPane(markdownField))
            .panel

        if (!showPanelOkCancel(panel, "Edit Description")) return null
        return node.copy(
            name = nameField.text.trim(),
            description = descField.text.trim(),
            markdownContent = markdownField.text
        )
    }

    fun editGroup(project: Project, node: BookmarkNode.Group): BookmarkNode.Group? {
        val nameField = JTextField(node.name)
        val descField = JTextField(node.description)
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name", nameField)
            .addLabeledComponent("Description", descField)
            .panel

        if (!showPanelOkCancel(panel, "Edit Group")) return null
        return node.copy(name = nameField.text.trim(), description = descField.text.trim())
    }

    fun editProcess(project: Project, node: BookmarkNode.Process): BookmarkNode.Process? {
        val nameField = JTextField(node.name)
        val descField = JTextField(node.description)
        val entryPathField = JTextField(node.entryFilePath ?: "")
        val entryLineField = JTextField(node.entryLine?.toString() ?: "")
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name", nameField)
            .addLabeledComponent("Description", descField)
            .addLabeledComponent("Entry file path", entryPathField)
            .addLabeledComponent("Entry line", entryLineField)
            .panel

        if (!showPanelOkCancel(panel, "Edit Process")) return null
        val entryPath = entryPathField.text.trim().ifBlank { null }
        if (!entryPath.isNullOrBlank() && !ensureFileExists(entryPath, "Edit Process")) return null
        val entryLine = entryLineField.text.trim().toIntOrNull()
        return node.copy(
            name = nameField.text.trim(),
            description = descField.text.trim(),
            entryFilePath = entryPath,
            entryLine = entryLine
        )
    }

    private fun showPanelOkCancel(panel: JPanel, title: String): Boolean {
        val result = Messages.showOkCancelDialog(panel, title, "Edit", Messages.getOkButton(), Messages.getCancelButton(), null)
        return result == Messages.OK
    }

    private fun ensureFileExists(path: String, title: String): Boolean {
        if (File(path).exists()) return true
        Messages.showErrorDialog("$title: File does not exist: $path", title)
        return false
    }
}
