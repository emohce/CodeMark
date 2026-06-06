package emohce.presentation.action

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

fun selectionReferenceTarget(project: Project, file: VirtualFile): String {
    ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
        VfsUtilCore.getRelativePath(file, root, '/')?.let { relativePath ->
            return "${root.name}/$relativePath"
        }
    }
    return selectionReferenceTarget(
        projectBasePaths = listOf(project.basePath),
        filePath = file.path,
        fileName = file.name
    )
}
