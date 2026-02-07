package emohce.presentation.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import emohce.core.di.ServiceLocator
import emohce.presentation.index.BookmarkIndexService
import emohce.presentation.toolwindow.BookmarkIntent

class DeleteCodeMarkAtCaretAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val indexService = BookmarkIndexService.getInstance(project)
        val caretLine = editor.caretModel.primaryCaret.logicalPosition.line
        val entry = indexService.entriesForFile(file.path).firstOrNull { it.line == caretLine } ?: return
        val confirmed = Messages.showYesNoDialog(
            project,
            "Delete CodeMark at this line?",
            "Delete CodeMark",
            Messages.getQuestionIcon()
        )
        if (confirmed == Messages.YES) {
            ServiceLocator.get(project).bookmarkViewModel.processIntent(BookmarkIntent.DeleteNode(entry.nodeId))
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (project == null || editor == null || file == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val indexService = BookmarkIndexService.getInstance(project)
        val caretLine = editor.caretModel.primaryCaret.logicalPosition.line
        val hasCodemark = indexService.entriesForFile(file.path).any { it.line == caretLine }
        e.presentation.isEnabledAndVisible = hasCodemark
    }
}
