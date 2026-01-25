package emohce.presentation.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiManager
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import emohce.presentation.selection.SelectionBus
import kotlinx.coroutines.runBlocking

class CreateBookmarkAtCaretAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val name = Messages.showInputDialog(project, "Bookmark name:", "Create Bookmark", null) ?: return
        if (name.isBlank()) return
        val description = Messages.showInputDialog(project, "Description (optional):", "Create Bookmark", null) ?: ""

        val caret = editor.caretModel.primaryCaret
        val line = caret.logicalPosition.line
        val column = caret.logicalPosition.column

        val bookmark = BookmarkNode.Bookmark(
            name = name.trim(),
            description = description.trim(),
            filePath = file.path,
            line = line,
            column = column
        )

        val parentId = SelectionBus.getInstance(project).getCurrentContainerId()
        runBlocking {
            val locator = ServiceLocator(project)
            // 通过 ViewModel 创建书签，确保 editor hints 和树形结构都自动刷新
            locator.bookmarkViewModel.processIntent(
                emohce.presentation.toolwindow.BookmarkIntent.CreateBookmark(parentId, bookmark, null)
            )
        }

        SelectionBus.getInstance(project).requestSelect(bookmark.uuid)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeRemarkTour")
            .createNotification("Bookmark created", NotificationType.INFORMATION)
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        val hasEditor = e.getData(CommonDataKeys.EDITOR) != null
        val hasFile = e.getData(CommonDataKeys.VIRTUAL_FILE) != null
        e.presentation.isEnabledAndVisible = hasEditor && hasFile
    }
}
