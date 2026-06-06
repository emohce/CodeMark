package emohce.presentation.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.vfs.VirtualFile
import emohce.presentation.action.adjustedSelectionEndOffset
import emohce.presentation.action.formatSelectionReference
import emohce.presentation.action.selectionReferenceTarget
import emohce.presentation.settings.EzCodeMarksSettings
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SelectionReferenceHintService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(SelectionReferenceHintService::class.java)
    private val attachedListeners = ConcurrentHashMap<Editor, SelectionHintListener>()
    @Volatile private var started = false

    fun start() {
        if (project.isDisposed || started) return
        started = true
        val fileEditorManager = FileEditorManager.getInstance(project)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                attachSelectionListener(file)
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                source.getAllEditors(file).filterIsInstance<TextEditor>().forEach { editor ->
                    detachSelectionListener(editor.editor)
                }
            }

            override fun selectionChanged(event: FileEditorManagerEvent) {
                event.newFile?.let { attachSelectionListener(it) }
            }
        })

        fileEditorManager.openFiles.forEach { attachSelectionListener(it) }
    }

    private fun attachSelectionListener(file: VirtualFile) {
        if (project.isDisposed) return
        FileEditorManager.getInstance(project).getEditors(file)
            .filterIsInstance<TextEditor>()
            .forEach { textEditor ->
                val editor = textEditor.editor
                if (attachedListeners.containsKey(editor)) return@forEach
                val listener = SelectionHintListener(editor, file)
                editor.selectionModel.addSelectionListener(listener)
                attachedListeners[editor] = listener
            }
    }

    private fun detachSelectionListener(editor: Editor) {
        val listener = attachedListeners.remove(editor) ?: return
        editor.selectionModel.removeSelectionListener(listener)
    }

    private inner class SelectionHintListener(
        private val editor: Editor,
        private val file: VirtualFile
    ) : SelectionListener {
        private var lastReference: String? = null

        override fun selectionChanged(e: SelectionEvent) {
            if (project.isDisposed) return
            if (!EzCodeMarksSettings.getInstance().selectionReferenceHintEnabled) return
            val caret = editor.caretModel.primaryCaret
            if (!caret.hasSelection()) {
                lastReference = null
                return
            }

            val document = editor.document
            val startLine = document.getLineNumber(caret.selectionStart)
            val endLine = document.getLineNumber(adjustedSelectionEndOffset(caret.selectionStart, caret.selectionEnd))
            val target = selectionReferenceTarget(project, file)
            val reference = formatSelectionReference(target, startLine, endLine)
            if (reference == lastReference) return
            lastReference = reference

            val shortcut = copySelectionReferenceShortcutText()
            val message = "Press $shortcut to copy $reference"
            try {
                PopupUtil.showBalloonForComponent(editor.contentComponent, message, MessageType.INFO, true, null)
            } catch (e: Exception) {
                logger.debug("[SELECTION_REF_HINT] Failed to show selection hint: ${e.message}")
            }
        }
    }

    private fun copySelectionReferenceShortcutText(): String {
        val action = ActionManager.getInstance().getAction("EzCodeMarks.CopySelectionReference")
        return action?.let { KeymapUtil.getFirstKeyboardShortcutText(it) }?.takeIf { it.isNotBlank() } ?: "Ctrl/Command+Shift+K"
    }

    override fun dispose() {
        attachedListeners.keys.forEach { detachSelectionListener(it) }
        attachedListeners.clear()
    }

    companion object {
        fun getInstance(project: Project): SelectionReferenceHintService = project.service()
    }
}
