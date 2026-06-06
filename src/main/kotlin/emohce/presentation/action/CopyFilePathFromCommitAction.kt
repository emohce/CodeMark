package emohce.presentation.action

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.StringSelection

class CopyFilePathFromCommitAction : DumbAwareAction() {
    private val logger = Logger.getInstance(CopyFilePathFromCommitAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return logger.warn("[ACTION_COPY_FILE_PATH] No project")
        val targets = pathTargets(e)
        if (targets.isEmpty()) return logger.warn("[ACTION_COPY_FILE_PATH] No file path")

        val projectRoots = projectRoots(project)
        val copied = targets
            .map { it.projectPrefixedPath(projectRoots) }
            .distinct()
            .joinToString("\n")
        CopyPasteManager.getInstance().setContents(StringSelection(copied))
        PopupUtil.showBalloonForActiveComponent("Copied: $copied", MessageType.INFO)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && pathTargets(e).isNotEmpty()
    }

    private fun pathTargets(e: AnActionEvent): List<PathTarget> {
        e.getData(VcsDataKeys.CHANGES)
            ?.mapNotNull { it.filePath()?.toPathTarget() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        e.getData(VcsDataKeys.CURRENT_CHANGE)?.let { change ->
            change.filePath()?.let { return listOf(it.toPathTarget()) }
        }

        e.getData(VcsDataKeys.FILE_PATH)?.let { filePath ->
            return listOf(filePath.toPathTarget())
        }

        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            ?.map { PathTarget(it.path, it.name) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { file ->
            return listOf(PathTarget(file.path, file.name))
        }

        riderSolutionPathTargets(e.dataContext)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        return emptyList()
    }

    private fun projectRoots(project: Project): List<String?> {
        return ProjectRootManager.getInstance(project).contentRoots.map { it.path } + project.basePath
    }

    private fun FilePath.toPathTarget(): PathTarget {
        return PathTarget(path = path, fileName = name)
    }

    private fun Change.filePath(): FilePath? {
        return afterRevision?.file ?: beforeRevision?.file
    }

    private fun PathTarget.projectPrefixedPath(projectRoots: List<String?>): String {
        return selectionReferenceTarget(projectRoots, path, fileName)
    }

    private fun riderSolutionPathTargets(dataContext: DataContext): List<PathTarget> {
        val views = runCatching {
            val viewClass = Class.forName("com.jetbrains.rider.projectView.ProjectElementViewKt")
            val viewMethod = viewClass.methods.firstOrNull { method ->
                method.name == "getProjectElementViews" && method.parameterCount == 2
            } ?: return emptyList()
            viewMethod.invoke(null, dataContext, false) as? Iterable<*>
        }.getOrNull() ?: return emptyList()

        return views.mapNotNull { view ->
            val virtualFile = view.invokeNoArg("getVirtualFile") as? VirtualFile
            if (virtualFile != null) {
                return@mapNotNull PathTarget(virtualFile.path, virtualFile.name)
            }

            val path = view.invokeNoArg("getPath")?.toString()
            val name = view.invokeNoArg("getName") as? String
            if (!path.isNullOrBlank() && !name.isNullOrBlank()) {
                PathTarget(path, name)
            } else {
                null
            }
        }
    }

    private fun Any?.invokeNoArg(methodName: String): Any? {
        if (this == null) return null
        return runCatching {
            javaClass.methods.firstOrNull { method ->
                method.name == methodName && method.parameterCount == 0
            }?.invoke(this)
        }.getOrNull()
    }

    private data class PathTarget(
        val path: String,
        val fileName: String
    )
}
