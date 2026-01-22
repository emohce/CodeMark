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

class CreateGroupAtCaretAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val name = Messages.showInputDialog(project, "Group name:", "Create Group", null) ?: return
        if (name.isBlank()) return

        val group = BookmarkNode.Group(name = name.trim())
        val parentId = SelectionBus.getInstance(project).getCurrentContainerId()
        runBlocking {
            val locator = ServiceLocator(project)
            locator.bookmarkRepository.create(group, parentId)
        }

        SelectionBus.getInstance(project).requestSelect(group.uuid)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeRemarkTour")
            .createNotification("Group created", NotificationType.INFORMATION)
            .notify(project)
    }
}
