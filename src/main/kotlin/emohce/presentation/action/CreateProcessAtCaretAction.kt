package emohce.presentation.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import emohce.presentation.selection.SelectionBus
import kotlinx.coroutines.runBlocking

class CreateProcessAtCaretAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val name = Messages.showInputDialog(project, "Process name:", "Create Process", null) ?: return
        if (name.isBlank()) return
        val description = Messages.showInputDialog(project, "Description (optional):", "Create Process", null) ?: ""

        val caret = editor.caretModel.primaryCaret
        val line = caret.logicalPosition.line

        val process = BookmarkNode.Process(
            name = name.trim(),
            description = description.trim(),
            entryFilePath = file.path,
            entryLine = line
        )

        val parentId = SelectionBus.getInstance(project).getCurrentContainerId()
        ApplicationManager.getApplication().executeOnPooledThread {
            runBlocking {
                val locator = ServiceLocator.get(project)
                locator.bookmarkRepository.create(process, parentId)
            }
        }

        SelectionBus.getInstance(project).requestSelect(process.uuid)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("EzCodeMarks")
            .createNotification("Process created", NotificationType.INFORMATION)
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        val hasEditor = e.getData(CommonDataKeys.EDITOR) != null
        val hasFile = e.getData(CommonDataKeys.VIRTUAL_FILE) != null
        e.presentation.isEnabledAndVisible = hasEditor && hasFile
    }
}
