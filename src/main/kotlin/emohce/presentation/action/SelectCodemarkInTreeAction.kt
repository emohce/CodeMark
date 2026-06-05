package emohce.presentation.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.ToolWindowManager
import emohce.presentation.index.BookmarkIndexService
import emohce.presentation.selection.SelectionBus

class SelectCodemarkInTreeAction : AnAction() {
    private val logger = Logger.getInstance(SelectCodemarkInTreeAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        logger.debug("[ACTION_SELECT_IN_TREE] Action triggered")
        val project = e.project ?: return logger.warn("[ACTION_SELECT_IN_TREE] No project")
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return logger.warn("[ACTION_SELECT_IN_TREE] No editor")
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return logger.warn("[ACTION_SELECT_IN_TREE] No file")

        val caret = editor.caretModel.primaryCaret
        val line = caret.logicalPosition.line

        val indexService = BookmarkIndexService.getInstance(project)
        val entry = indexService.entriesForFile(file.path).firstOrNull { it.line == line }
        
        if (entry != null) {
            logger.debug("[ACTION_SELECT_IN_TREE] Found codemark at line $line, nodeId=${entry.nodeId}")
            SelectionBus.getInstance(project).requestSelect(entry.nodeId, file.path, line, focusTree = true)
        } else {
            logger.debug("[ACTION_SELECT_IN_TREE] No codemark found at line $line")
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val hasEditor = editor != null
        val hasFile = file != null
        e.presentation.isEnabledAndVisible = hasEditor && hasFile && project != null
    }
}
