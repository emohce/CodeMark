package emohce.presentation.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import emohce.core.di.ServiceLocator
import emohce.presentation.editor.BookmarkDocumentListener
import emohce.presentation.toolwindow.panel.BookmarkPanel

class CodeRemarkTourToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val locator = ServiceLocator(project)
        val viewModel = BookmarkViewModel(
            project = project,
            bookmarkRepository = locator.bookmarkRepository,
            referenceRepository = locator.referenceRepository,
            processNavigationUseCase = locator.processNavigationUseCase,
            syncReferencesUseCase = locator.syncReferencesUseCase,
            detectCircularRefUseCase = locator.detectCircularRefUseCase,
            dispatchers = locator.dispatchers
        )

        // 初始化文档监听器
        val documentListener = BookmarkDocumentListener(project)
        documentListener.setViewModel(viewModel)
        viewModel.setDocumentListener(documentListener)

        val panel = BookmarkPanel(project, viewModel)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
