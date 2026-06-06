package emohce.presentation.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.util.PopupUtil
import java.awt.datatransfer.StringSelection

class CopySelectionReferenceAction : AnAction() {
    private val logger = Logger.getInstance(CopySelectionReferenceAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return logger.warn("[ACTION_COPY_SELECTION_REF] No project")
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return logger.warn("[ACTION_COPY_SELECTION_REF] No editor")
        val file = FileDocumentManager.getInstance().getFile(editor.document)
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: return logger.warn("[ACTION_COPY_SELECTION_REF] No file")
        val caret = editor.caretModel.primaryCaret
        if (!caret.hasSelection()) return logger.debug("[ACTION_COPY_SELECTION_REF] No selection")

        val document = editor.document
        val startLine = document.getLineNumber(caret.selectionStart)
        val endLine = document.getLineNumber(adjustedSelectionEndOffset(caret.selectionStart, caret.selectionEnd))
        val target = selectionReferenceTarget(project, file)
        val reference = formatSelectionReference(target, startLine, endLine)

        CopyPasteManager.getInstance().setContents(StringSelection(reference))
        PopupUtil.showBalloonForActiveComponent("Copied $reference", MessageType.INFO)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && editor?.caretModel?.primaryCaret?.hasSelection() == true
    }

}
