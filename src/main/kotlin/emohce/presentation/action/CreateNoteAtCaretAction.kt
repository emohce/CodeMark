package emohce.presentation.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import emohce.presentation.selection.SelectionBus
import kotlinx.coroutines.runBlocking

class CreateNoteAtCaretAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val title = Messages.showInputDialog(project, "Note title:", "Create Note", null) ?: return
        if (title.isBlank()) return
        val description = Messages.showInputDialog(project, "Description (optional):", "Create Note", null) ?: ""
        val markdown = Messages.showInputDialog(project, "Markdown (optional):", "Create Note", null) ?: ""

        val note = BookmarkNode.DescriptiveBookmark(
            name = title.trim(),
            description = description.trim(),
            markdownContent = markdown
        )

        val parentId = SelectionBus.getInstance(project).getCurrentContainerId()
        runBlocking {
            val locator = ServiceLocator.get(project)
            locator.bookmarkRepository.create(note, parentId)
        }

        SelectionBus.getInstance(project).requestSelect(note.uuid)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeMark")
            .createNotification("Note created", NotificationType.INFORMATION)
            .notify(project)
    }
}
