package emohce.presentation.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.FormBuilder
import emohce.domain.model.BookmarkNode
import java.awt.GridLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke

object BookmarkEditDialogUtil {

    fun editBookmark(project: Project, node: BookmarkNode.Bookmark): BookmarkNode.Bookmark? {
        val nameField = JTextField(node.name)
        val descField = multilineTextArea(node.description, 3)
        val pathField = JTextField(bookmarkDisplayPath(node.filePath, project.basePath))
        val lineField = JTextField(node.line.toString())
        val columnField = JTextField(node.column.toString())
        val lineColumnPanel = JPanel(GridLayout(1, 2, 8, 0)).apply {
            add(lineField)
            add(columnField)
        }
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name", nameField)
            .addLabeledComponent("Description", JScrollPane(descField))
            .addLabeledComponent("Line / Column", lineColumnPanel)
            .addLabeledComponent("File path", pathField)
            .panel

        val fields = listOf(nameField, descField, lineField, columnField, pathField)
        setupCommandNumberNavigation(fields)
        setupTextAreaNavigation(descField, fields, 1)

        if (!showPanelOkCancel(project, panel, "Edit Bookmark", nameField)) return null
        val path = bookmarkStoragePath(pathField.text.trim(), project.basePath)
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
        val markdownField = JTextArea(node.markdownContent, 6, 40).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name", nameField)
            .addLabeledComponent("Description", descField)
            .addLabeledComponent("Markdown", JScrollPane(markdownField))
            .panel

        val fields = listOf(nameField, descField, markdownField)
        setupCommandNumberNavigation(fields)
        setupTextAreaNavigation(markdownField, fields, 2)

        if (!showPanelOkCancel(project, panel, "Edit Description", nameField)) return null
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

        val fields = listOf(nameField, descField)
        setupCommandNumberNavigation(fields)

        if (!showPanelOkCancel(project, panel, "Edit Group", nameField)) return null
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

        val fields = listOf(nameField, descField, entryPathField, entryLineField)
        setupCommandNumberNavigation(fields)

        if (!showPanelOkCancel(project, panel, "Edit Process", nameField)) return null
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

    private fun showPanelOkCancel(project: Project, panel: JPanel, title: String, preferredFocus: JComponent? = null): Boolean {
        val dialog = object : DialogWrapper(project) {
            init {
                this.title = title
                init()
            }

            override fun createCenterPanel(): JComponent = panel

            override fun getPreferredFocusedComponent(): JComponent? = preferredFocus
        }
        return dialog.showAndGet()
    }

    private fun ensureFileExists(path: String, title: String): Boolean {
        if (File(path).exists()) return true
        Messages.showErrorDialog("$title: File does not exist: $path", title)
        return false
    }

    private fun multilineTextArea(text: String, rows: Int): JTextArea {
        return JTextArea(text, rows, 40).apply {
            lineWrap = true
            wrapStyleWord = true
        }
    }

    private fun setupCommandNumberNavigation(fields: List<JComponent>) {
        fields.forEachIndexed { index, field ->
            val action = object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    field.requestFocusInWindow()
                }
            }
            val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_1 + index, KeyEvent.META_DOWN_MASK)
            field.inputMap.put(keyStroke, "cmd$index")
            field.actionMap.put("cmd$index", action)
        }
    }

    private fun setupTextAreaNavigation(textArea: JTextArea, fields: List<JComponent>, fieldIndex: Int) {
        val nextField = if (fieldIndex < fields.size - 1) fields[fieldIndex + 1] else fields[0]
        val prevField = if (fieldIndex > 0) fields[fieldIndex - 1] else fields[fields.size - 1]

        // Tab: navigate to next field
        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "tabNext")
        textArea.actionMap.put("tabNext", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                nextField.requestFocusInWindow()
            }
        })

        // Shift+Tab: navigate to previous field
        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK), "tabPrev")
        textArea.actionMap.put("tabPrev", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                prevField.requestFocusInWindow()
            }
        })

        // Shift+Enter: insert newline
        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "shiftEnter")
        textArea.actionMap.put("shiftEnter", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                textArea.insert("\n", textArea.caretPosition)
            }
        })
    }
}

internal fun bookmarkDisplayPath(filePath: String, basePath: String?): String {
    if (filePath.isBlank() || basePath.isNullOrBlank()) return filePath
    return runCatching {
        val base = Path.of(basePath).normalize()
        val path = Path.of(filePath).normalize()
        if (path.startsWith(base)) {
            base.relativize(path).toString().replace(File.separatorChar, '/').ifBlank { "." }
        } else {
            path.toString().replace(File.separatorChar, '/')
        }
    }.getOrDefault(filePath.replace(File.separatorChar, '/'))
}

internal fun bookmarkStoragePath(displayPath: String, basePath: String?): String {
    if (displayPath.isBlank() || basePath.isNullOrBlank()) return displayPath
    val normalized = displayPath.replace('\\', '/')
    val file = File(normalized)
    if (file.isAbsolute) return normalized
    return Path.of(basePath, normalized).normalize().toString().replace(File.separatorChar, '/')
}
