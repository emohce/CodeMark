package emohce.core.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import emohce.presentation.editor.highlighter.BookmarkHighlighterService

/**
 * 启动项目时开启自定义书签高亮/联动服务，确保即使未打开工具窗口也可工作。
 */
class BookmarkStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        BookmarkHighlighterService.getInstance(project).start()
    }
}
