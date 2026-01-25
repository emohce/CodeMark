package emohce.presentation.editor.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.util.Function
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import emohce.presentation.selection.SelectionBus
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

class BookmarkLineMarkerProvider : LineMarkerProviderDescriptor() {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val bookmarkIcon: Icon = AllIcons.Nodes.Bookmark
    private val processIcon: Icon = AllIcons.Actions.Execute
    private val fallbackIcon: Icon = IconLoader.getIcon("/META-INF/pluginIcon.svg", BookmarkLineMarkerProvider::class.java)

    override fun getName(): String = "CodeRemarkTour"

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(element.project).getDocument(file) ?: return null
        val line = document.getLineNumber(element.textOffset)
        val lineStart = document.getLineStartOffset(line)
        if (element.textRange.startOffset != lineStart) return null

        val markers = getMarkers(element.project, virtualFile)
        val marker = markers.firstOrNull { it.line == line } ?: return null

        return LineMarkerInfo(
            element,
            element.textRange,
            marker.icon,
            Function { marker.tooltip },
            { _, _ -> navigateToLine(element.project, virtualFile, marker) },
            com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment.LEFT,
            { marker.tooltip }
        )
    }

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        val processedLines = mutableSetOf<Int>()
        val file = elements.firstOrNull()?.containingFile?.virtualFile ?: return
        val document = elements.firstOrNull()?.let { 
            PsiDocumentManager.getInstance(it.project).getDocument(it.containingFile) 
        } ?: return
        
        for (element in elements) {
            val line = document.getLineNumber(element.textOffset)
            if (processedLines.contains(line)) continue
            
            val marker = getLineMarkerInfo(element)
            if (marker != null) {
                result.add(marker)
                processedLines.add(line)
            }
        }
    }

    private fun getMarkers(project: Project, virtualFile: VirtualFile): List<MarkerEntry> {
        val entry = cache[virtualFile.path]
        val now = System.currentTimeMillis()
        if (entry != null && now - entry.timestamp < 2000) {
            return entry.markers
        }
        val markers = runBlocking {
            val locator = ServiceLocator(project)
            val root = locator.bookmarkRepository.getRootNode()
            collectMarkers(root, virtualFile.path)
        }
        cache[virtualFile.path] = CacheEntry(markers, now)
        return markers
    }

    private fun collectMarkers(root: BookmarkNode, filePath: String): List<MarkerEntry> {
        val markers = mutableListOf<MarkerEntry>()
        val seenLines = mutableSetOf<Int>()
        traverse(root) { node ->
            when (node) {
                is BookmarkNode.Bookmark -> {
                    if (node.filePath == filePath && !seenLines.contains(node.line)) {
                        seenLines.add(node.line)
                        markers.add(
                            MarkerEntry(
                                line = node.line,
                                nodeId = node.uuid,
                                icon = bookmarkIcon,
                                tooltip = buildBookmarkTooltip(node)
                            )
                        )
                    }
                }
                is BookmarkNode.Process -> {
                    val entryLine = node.entryLine
                    if (node.entryFilePath == filePath && entryLine != null && !seenLines.contains(entryLine)) {
                        seenLines.add(entryLine)
                        markers.add(
                            MarkerEntry(
                                line = entryLine,
                                nodeId = node.uuid,
                                icon = processIcon,
                                tooltip = buildProcessTooltip(node)
                            )
                        )
                    }
                }
                else -> Unit
            }
        }
        if (markers.isEmpty()) return markers
        return markers.sortedBy { it.line }
    }

    private fun traverse(node: BookmarkNode, visitor: (BookmarkNode) -> Unit) {
        visitor(node)
        when (node) {
            is BookmarkNode.Group -> node.children.forEach { traverse(it, visitor) }
            is BookmarkNode.Process -> node.steps.forEach { traverse(it, visitor) }
            else -> Unit
        }
    }

    private fun navigateToLine(project: Project, file: VirtualFile, marker: MarkerEntry) {
        OpenFileDescriptor(project, file, marker.line, 0).navigate(true)
        SelectionBus.getInstance(project).requestSelect(marker.nodeId)
    }

    private fun buildBookmarkTooltip(node: BookmarkNode.Bookmark): String {
        val title = node.name.ifBlank { "Bookmark" }
        val description = node.description.trim().replace("\n", " ")
        return if (description.isNotBlank()) "$title - $description" else title
    }

    private fun buildProcessTooltip(node: BookmarkNode.Process): String {
        val title = node.name.ifBlank { "Process entry" }
        val description = node.description.trim().replace("\n", " ")
        return if (description.isNotBlank()) "Process: $title - $description" else "Process: $title"
    }

    private data class MarkerEntry(
        val line: Int,
        val nodeId: String,
        val icon: Icon,
        val tooltip: String
    )

    private data class CacheEntry(val markers: List<MarkerEntry>, val timestamp: Long)
}
