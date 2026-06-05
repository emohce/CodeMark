package emohce.presentation.toolwindow

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
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
        return editBookmark(project, node, "Edit Bookmark")
    }

    fun editBookmark(project: Project, node: BookmarkNode.Bookmark, title: String): BookmarkNode.Bookmark? {
        val nameField = JTextField(node.name)
        val descField = multilineEditorTextField(project, node.description, 5)
        val descScrollPane = JScrollPane(descField)
        val pathField = JTextField(bookmarkDisplayPath(node.filePath, project.basePath))
        val lineField = JTextField(node.line.toString())
        val columnField = JTextField(node.column.toString())
        val lineColumnPanel = JPanel(GridLayout(1, 2, 8, 0)).apply {
            add(lineField)
            add(columnField)
        }
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name", nameField)
            .addLabeledComponent("Description", descScrollPane)
            .addLabeledComponent("Line / Column", lineColumnPanel)
            .addLabeledComponent("File path", pathField)
            .panel

        // Auto-expand description field on focus and text change
        fun updateDescHeight() {
            val editor = descField.editor as? EditorImpl ?: return
            val lineCount = descField.document.lineCount
            val newRows = maxOf(5, minOf(lineCount, 10))
            val lineHeight = editor.lineHeight
            val newHeight = lineHeight * newRows + 8
            descField.preferredSize = java.awt.Dimension(400, newHeight)
            descScrollPane.preferredSize = java.awt.Dimension(450, newHeight + 10)
            descScrollPane.revalidate()
            descScrollPane.repaint()
        }
        descField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) {
                updateDescHeight()
            }
        })
        descField.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = updateDescHeight()
        })

        // Register F2 key using InputMap/ActionMap
        val f2Action = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                openEditorInDialog(project, descField, "Edit Description")
            }
        }
        descField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "f2")
        descField.actionMap.put("f2", f2Action)

        val fields = listOf(nameField, descField, lineField, columnField, pathField)
        setupCommandNumberNavigation(fields)
        setupEditorTextFieldNavigation(descField, fields, 1)

        val result = showPanelOkCancelWithEditor(project, panel, title, nameField, descField)
        if (!result) return null
        val path = bookmarkStoragePath(pathField.text.trim(), project.basePath)
        if (!ensureFileExists(path, title)) return null
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
        val markdownField = multilineEditorTextField(project, node.markdownContent, 6)
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name", nameField)
            .addLabeledComponent("Description", descField)
            .addLabeledComponent("Markdown", JScrollPane(markdownField))
            .panel

        val fields = listOf(nameField, descField, markdownField)
        setupCommandNumberNavigation(fields)
        setupEditorTextFieldNavigation(markdownField, fields, 2)

        val result = showPanelOkCancelWithEditor(project, panel, "Edit Description", nameField, markdownField)
        if (!result) return null
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

    private fun showPanelOkCancelWithEditor(project: Project, panel: JPanel, title: String, preferredFocus: JComponent?, editorField: EditorTextField): Boolean {
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

    private fun multilineEditorTextField(project: Project, text: String, rows: Int): EditorTextField {
        val document = EditorFactory.getInstance().createDocument(text)
        val editorTextField = EditorTextField(document, project, null, false).apply {
            setPreferredWidth(400)
            setOneLineMode(false)
            preferredSize = java.awt.Dimension(400, rows * 16)
        }
        return editorTextField
    }

    private fun openEditorInDialog(project: Project, editorField: EditorTextField, title: String) {
        val document = editorField.document
        val editor = EditorFactory.getInstance().createEditor(document, project)
        val editorComponent = editor.component
        
        val dialog = object : DialogWrapper(project) {
            init {
                this.title = title
                setOKActionEnabled(false)
                setCancelButtonText("")
                init()
            }

            override fun createCenterPanel(): JComponent {
                val panel = JPanel(java.awt.BorderLayout())
                panel.add(editorComponent, java.awt.BorderLayout.CENTER)
                panel.preferredSize = java.awt.Dimension(600, 400)
                return panel
            }

            override fun doCancelAction() {
                // ESC closes the dialog, content is already synced
                super.doCancelAction()
            }

            override fun dispose() {
                EditorFactory.getInstance().releaseEditor(editor)
                super.dispose()
            }
        }
        
        dialog.show()
    }

    private fun setupCommandNumberNavigation(fields: List<JComponent>) {
        fields.forEachIndexed { index, field ->
            // Disable default focus traversal for all fields
            field.focusTraversalKeysEnabled = false

            // For JTextField, move caret to start on focus gain instead of selecting all
            if (field is JTextField) {
                field.addFocusListener(object : java.awt.event.FocusAdapter() {
                    override fun focusGained(e: java.awt.event.FocusEvent) {
                        field.caretPosition = 0
                        field.select(0, 0)
                    }
                })

                // Add custom Tab navigation for JTextField
                val nextField = if (index < fields.size - 1) fields[index + 1] else fields[0]
                val prevField = if (index > 0) fields[index - 1] else fields[fields.size - 1]

                field.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "tabNext")
                field.actionMap.put("tabNext", object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent) {
                        nextField.requestFocusInWindow()
                    }
                })

                field.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK), "tabPrev")
                field.actionMap.put("tabPrev", object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent) {
                        prevField.requestFocusInWindow()
                    }
                })
            }

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

    private fun setupEditorTextFieldNavigation(editorField: EditorTextField, fields: List<JComponent>, fieldIndex: Int) {
        val nextField = if (fieldIndex < fields.size - 1) fields[fieldIndex + 1] else fields[0]
        val prevField = if (fieldIndex > 0) fields[fieldIndex - 1] else fields[fields.size - 1]

        // Disable default focus traversal to allow custom Tab handling
        editorField.focusTraversalKeysEnabled = false

        // Move caret to start on focus gain instead of selecting all
        editorField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) {
                editorField.editor?.caretModel?.moveToOffset(0)
            }
        })

        // Tab: navigate to next field
        editorField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "tabNext")
        editorField.actionMap.put("tabNext", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                nextField.requestFocusInWindow()
            }
        })

        // Shift+Tab: navigate to previous field
        editorField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK), "tabPrev")
        editorField.actionMap.put("tabPrev", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                prevField.requestFocusInWindow()
            }
        })

        // Shift+Enter: insert newline
        editorField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "shiftEnter")
        editorField.actionMap.put("shiftEnter", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val editor = editorField.editor
                if (editor != null) {
                    editor.document.insertString(editor.caretModel.offset, "\n")
                }
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
