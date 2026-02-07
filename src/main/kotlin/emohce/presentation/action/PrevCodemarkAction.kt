package emohce.presentation.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import emohce.core.di.ServiceLocator
import emohce.presentation.selection.SelectionBus
import emohce.presentation.toolwindow.BookmarkIntent
import kotlinx.coroutines.runBlocking

class PrevCodemarkAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val locator = ServiceLocator.get(project)
        val currentId = SelectionBus.getInstance(project).getLastSelectedNodeId()
        val entry = runBlocking {
            locator.globalCodemarkNavigationUseCase.findPrevious(currentId)
        } ?: return
        SelectionBus.getInstance(project).setLastSelectedNodeId(entry.nodeId)
        CodemarkNavigationHelper.navigateToEntry(project, entry.filePath, entry.line, entry.column)
        locator.bookmarkViewModel.processIntent(BookmarkIntent.NavigateToNode(entry.nodeId))
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
