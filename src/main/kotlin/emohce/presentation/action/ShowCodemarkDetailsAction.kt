package emohce.presentation.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import emohce.presentation.toolwindow.panel.BookmarkPanel

class ShowCodemarkDetailsAction : AnAction() {
    private val logger = Logger.getInstance(ShowCodemarkDetailsAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return logger.warn("[ACTION_SHOW_DETAILS] No project")
        val panel = BookmarkPanel.findActive(project) ?: return logger.warn("[ACTION_SHOW_DETAILS] No active BookmarkPanel")
        if (!panel.showCurrentNodeDetails()) {
            logger.debug("[ACTION_SHOW_DETAILS] No codemark details to show")
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && BookmarkPanel.findActive(project) != null
    }
}
