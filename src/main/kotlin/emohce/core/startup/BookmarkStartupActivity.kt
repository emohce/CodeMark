package emohce.core.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import emohce.presentation.editor.SelectionReferenceHintService
import emohce.presentation.editor.highlighter.BookmarkHighlighterService

/**
 * 启动项目时开启自定义书签高亮/联动服务，确保即使未打开工具窗口也可工作。
 */
class BookmarkStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (project.isDisposed) return
        BookmarkHighlighterService.getInstance(project).start()
        SelectionReferenceHintService.getInstance(project).start()
    }
}
