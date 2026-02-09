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
import emohce.presentation.index.BookmarkIndexService
import emohce.presentation.selection.SelectionBus
import emohce.presentation.toolwindow.BookmarkEditDialogUtil
import emohce.presentation.toolwindow.BookmarkIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class CreateBookmarkAtCaretAction : AnAction() {
    private val logger = Logger.getInstance(CreateBookmarkAtCaretAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        logger.info("[ACTION_CREATE_BOOKMARK] Action triggered")
        val project = e.project ?: return logger.warn("[ACTION_CREATE_BOOKMARK] No project")
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return logger.warn("[ACTION_CREATE_BOOKMARK] No editor")
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return logger.warn("[ACTION_CREATE_BOOKMARK] No file")

        val caret = editor.caretModel.primaryCaret
        val line = caret.logicalPosition.line
        val column = caret.logicalPosition.column

        val indexService = BookmarkIndexService.getInstance(project)
        val existingEntry = indexService.entriesForFile(file.path).firstOrNull { it.line == line }
        val existingNode = if (existingEntry != null) {
            runBlocking {
                withContext(Dispatchers.IO) {
                    ServiceLocator.get(project).bookmarkRepository.findByUuid(existingEntry.nodeId)
                }
            }
        } else null

        if (existingNode != null) {
            val edited = when (existingNode) {
                is BookmarkNode.Bookmark -> BookmarkEditDialogUtil.editBookmark(project, existingNode)
                is BookmarkNode.DescriptiveBookmark -> BookmarkEditDialogUtil.editDescriptive(project, existingNode)
                is BookmarkNode.Group -> BookmarkEditDialogUtil.editGroup(project, existingNode)
                is BookmarkNode.Process -> BookmarkEditDialogUtil.editProcess(project, existingNode)
                else -> null
            } ?: return
            runBlocking {
                val locator = ServiceLocator.get(project)
                locator.bookmarkViewModel.processIntent(BookmarkIntent.EditNode(edited))
            }
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeMark")
                .createNotification("CodeMark updated", NotificationType.INFORMATION)
                .notify(project)
            return
        }

        logger.info("[ACTION_CREATE_BOOKMARK] Showing input dialog for bookmark name...")
        val name = Messages.showInputDialog(project, "CodeMark name:", "Create CodeMark", null) ?: return logger.info("[ACTION_CREATE_BOOKMARK] User cancelled name input")
        if (name.isBlank()) return logger.warn("[ACTION_CREATE_BOOKMARK] Name is blank")
        val description = Messages.showInputDialog(project, "Description (optional):", "Create CodeMark", null) ?: ""

        logger.info("[ACTION_CREATE_BOOKMARK] Creating bookmark object: name=$name, filePath=${file.path}, line=$line, column=$column")
        val bookmark = BookmarkNode.Bookmark(
            name = name.trim(),
            description = description.trim(),
            filePath = file.path,
            line = line,
            column = column
        )
        logger.info("[ACTION_CREATE_BOOKMARK] Bookmark created with uuid=${bookmark.uuid}")

        runBlocking {
            val locator = ServiceLocator.get(project)
            val (parentId, insertIndex) = locator.bookmarkViewModel.getInsertionTarget(
                SelectionBus.getInstance(project).getLastSelectedNodeId()
            )
            logger.info("[ACTION_CREATE_BOOKMARK] ParentId=$parentId, insertIndex=$insertIndex, calling processIntent...")
            locator.bookmarkViewModel.processIntent(
                BookmarkIntent.CreateBookmark(parentId, bookmark, insertIndex)
            )
        }
        logger.info("[ACTION_CREATE_BOOKMARK] processIntent completed, SelectNode side effect will handle tree selection")
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeMark")
            .createNotification("CodeMark created", NotificationType.INFORMATION)
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val hasEditor = editor != null
        val hasFile = file != null
        e.presentation.isEnabledAndVisible = hasEditor && hasFile && project != null
        if (hasEditor && hasFile && project != null) {
            val indexService = BookmarkIndexService.getInstance(project)
            val caretLine = editor!!.caretModel.primaryCaret.logicalPosition.line
            val hasCodemark = indexService.entriesForFile(file!!.path).any { it.line == caretLine }
            e.presentation.text = if (hasCodemark) "Edit CodeMark" else "Add CodeMark Here"
        }
    }
}
