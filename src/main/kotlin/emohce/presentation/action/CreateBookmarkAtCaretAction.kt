package emohce.presentation.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiManager
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import emohce.presentation.selection.SelectionBus
import kotlinx.coroutines.runBlocking

class CreateBookmarkAtCaretAction : AnAction() {
    private val logger = Logger.getInstance(CreateBookmarkAtCaretAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        logger.info("[ACTION_CREATE_BOOKMARK] Action triggered")
        val project = e.project ?: return logger.warn("[ACTION_CREATE_BOOKMARK] No project")
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return logger.warn("[ACTION_CREATE_BOOKMARK] No editor")
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return logger.warn("[ACTION_CREATE_BOOKMARK] No file")

        logger.info("[ACTION_CREATE_BOOKMARK] Showing input dialog for bookmark name...")
        val name = Messages.showInputDialog(project, "CodeMark name:", "Create CodeMark", null) ?: return logger.info("[ACTION_CREATE_BOOKMARK] User cancelled name input")
        if (name.isBlank()) return logger.warn("[ACTION_CREATE_BOOKMARK] Name is blank")
        val description = Messages.showInputDialog(project, "Description (optional):", "Create CodeMark", null) ?: ""

        val caret = editor.caretModel.primaryCaret
        val line = caret.logicalPosition.line
        val column = caret.logicalPosition.column

        logger.info("[ACTION_CREATE_BOOKMARK] Creating bookmark object: name=$name, filePath=${file.path}, line=$line, column=$column")
        val bookmark = BookmarkNode.Bookmark(
            name = name.trim(),
            description = description.trim(),
            filePath = file.path,
            line = line,
            column = column
        )
        logger.info("[ACTION_CREATE_BOOKMARK] Bookmark created with uuid=${bookmark.uuid}")

        val parentId = SelectionBus.getInstance(project).getCurrentContainerId()
        logger.info("[ACTION_CREATE_BOOKMARK] ParentId=$parentId, calling processIntent...")
        runBlocking {
            val locator = ServiceLocator(project)
            // 通过 ViewModel 创建书签，确保 editor hints 和树形结构都自动刷新
            locator.bookmarkViewModel.processIntent(
                emohce.presentation.toolwindow.BookmarkIntent.CreateBookmark(parentId, bookmark, null)
            )
        }
        logger.info("[ACTION_CREATE_BOOKMARK] processIntent completed, SelectNode side effect will handle tree selection")
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeRemarkTour")
            .createNotification("CodeMark created", NotificationType.INFORMATION)
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        val hasEditor = e.getData(CommonDataKeys.EDITOR) != null
        val hasFile = e.getData(CommonDataKeys.VIRTUAL_FILE) != null
        e.presentation.isEnabledAndVisible = hasEditor && hasFile
    }
}
