package emohce.presentation.editor.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
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
    private val logger = Logger.getInstance(BookmarkLineMarkerProvider::class.java)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val bookmarkIcon: Icon = AllIcons.Nodes.Bookmark
    private val processIcon: Icon = AllIcons.Actions.Execute
    private val fallbackIcon: Icon = IconLoader.getIcon("/META-INF/pluginIcon.svg", BookmarkLineMarkerProvider::class.java)

    companion object {
        @Volatile
        private var instance: BookmarkLineMarkerProvider? = null
        
        fun getInstance(): BookmarkLineMarkerProvider? = instance
        
        /**
         * 清除指定文件的缓存，强制重新收集标记
         */
        fun clearCache(filePath: String) {
            instance?.clearCacheInternal(filePath)
        }
        
        /**
         * 清除所有缓存
         */
        fun clearAllCache() {
            instance?.clearAllCacheInternal()
        }
    }

    init {
        logger.info("[GUTTER_INIT] BookmarkLineMarkerProvider initialized")
        instance = this
    }
    
    /**
     * 清除指定文件的缓存，强制重新收集标记
     */
    private fun clearCacheInternal(filePath: String) {
        val normalizedPath = com.intellij.openapi.util.io.FileUtil.toSystemIndependentName(filePath)
        val removed = cache.remove(normalizedPath)
        logger.info("[GUTTER_CLEAR_CACHE] Cleared cache for file=$normalizedPath, hadEntry=${removed != null}")
    }
    
    /**
     * 清除所有缓存
     */
    private fun clearAllCacheInternal() {
        val count = cache.size
        cache.clear()
        logger.info("[GUTTER_CLEAR_CACHE] Cleared all cache, removed $count entries")
    }

    override fun getName(): String = "CodeRemarkTour"

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // 返回 null，让 collectSlowLineMarkers 处理所有标记
        // 这样可以确保所有标记都被正确收集，特别是在 daemon 重启后
        return null
    }

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        logger.warn("[GUTTER_COLLECT] begin, elements=${elements.size}")
        if (elements.isEmpty()) {
            logger.debug("collectSlowLineMarkers: elements is empty, returning")
            return
        }
        
        val firstElement = elements.first()
        val file = firstElement.containingFile
        if (file == null) {
            logger.warn("collectSlowLineMarkers: containingFile is null")
            return
        }
        val virtualFile = file.virtualFile
        if (virtualFile == null) {
            logger.warn("collectSlowLineMarkers: virtualFile is null")
            return
        }
        val project = firstElement.project
        val document = PsiDocumentManager.getInstance(project).getDocument(file)
        if (document == null) {
            logger.warn("collectSlowLineMarkers: document is null")
            return
        }
        
        logger.info("collectSlowLineMarkers: file=${virtualFile.path}, lineCount=${document.lineCount}")
        
        val markers = getMarkers(project, virtualFile)
        logger.warn("[GUTTER_COLLECT] markers=${markers.size} file=${virtualFile.path}")
        if (markers.isEmpty()) {
            logger.debug("collectSlowLineMarkers: no markers found, returning")
            return
        }
        
        markers.forEach { marker ->
            logger.debug("collectSlowLineMarkers: marker at line ${marker.line}, nodeId=${marker.nodeId}, tooltip=${marker.tooltip}")
        }
        
        val processedLines = mutableSetOf<Int>()
        
        // Find elements at the start of each line that has a bookmark
        for (marker in markers) {
            if (processedLines.contains(marker.line)) {
                logger.debug("collectSlowLineMarkers: line ${marker.line} already processed, skipping")
                continue
            }
            if (marker.line < 0 || marker.line >= document.lineCount) {
                logger.warn("collectSlowLineMarkers: invalid line ${marker.line}, document has ${document.lineCount} lines")
                continue
            }
            
            val lineStart = document.getLineStartOffset(marker.line)
            val lineEnd = document.getLineEndOffset(marker.line)
            logger.debug("collectSlowLineMarkers: looking for element at line ${marker.line}, range=[$lineStart, $lineEnd)")
            
            // Find an element that starts at the beginning of this line
            val elementAtLine = elements.firstOrNull { element ->
                val elementStart = element.textRange.startOffset
                val elementEnd = element.textRange.endOffset
                val matches = elementStart >= lineStart && elementStart < lineEnd
                if (matches) {
                    logger.debug("collectSlowLineMarkers: found matching element at line ${marker.line}, elementStart=$elementStart, elementEnd=$elementEnd, elementType=${element.javaClass.simpleName}")
                }
                matches
            }
            
            if (elementAtLine == null) {
                logger.warn("collectSlowLineMarkers: no element found for line ${marker.line}, lineStart=$lineStart, lineEnd=$lineEnd")
                // Try to find any element on this line
                val anyElementOnLine = elements.firstOrNull { element ->
                    val elementStart = element.textRange.startOffset
                    val elementEnd = element.textRange.endOffset
                    elementStart < lineEnd && elementEnd > lineStart
                }
                if (anyElementOnLine != null) {
                    logger.info("collectSlowLineMarkers: using alternative element for line ${marker.line}, elementStart=${anyElementOnLine.textRange.startOffset}")
                    processedLines.add(marker.line)
                    val lineMarkerInfo = LineMarkerInfo(
                        anyElementOnLine,
                        anyElementOnLine.textRange,
                        marker.icon,
                        Function { marker.tooltip },
                        { _, _ -> navigateToLine(project, virtualFile, marker) },
                        com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment.LEFT,
                        { marker.tooltip }
                    )
                    result.add(lineMarkerInfo)
                    logger.info("collectSlowLineMarkers: added LineMarkerInfo for line ${marker.line}")
                }
                continue
            }
            
            processedLines.add(marker.line)
            
            val lineMarkerInfo = LineMarkerInfo(
                elementAtLine,
                elementAtLine.textRange,
                marker.icon,
                Function { marker.tooltip },
                { _, _ ->
                    logger.warn("[GUTTER_CLICK] handler invoked element=${elementAtLine.javaClass.simpleName} line=${marker.line} id=${marker.nodeId}")
                    navigateToLine(project, virtualFile, marker)
                },
                com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment.LEFT,
                { marker.tooltip }
            )
            result.add(lineMarkerInfo)
            logger.info("collectSlowLineMarkers: added LineMarkerInfo for line ${marker.line}, elementType=${elementAtLine.javaClass.simpleName}")
        }
        
        logger.info("collectSlowLineMarkers: completed, added ${result.size} markers")
    }

    private fun getMarkers(project: Project, virtualFile: VirtualFile): List<MarkerEntry> {
        val normalizedPath = FileUtil.toSystemIndependentName(virtualFile.path)
        logger.info("[GUTTER_GET_MARKERS] Step 1: Getting markers for file=$normalizedPath")
        
        val entry = cache[normalizedPath]
        val now = System.currentTimeMillis()
        if (entry != null && now - entry.timestamp < 2000) {
            logger.info("[GUTTER_GET_MARKERS] Step 2: Using cached markers, count=${entry.markers.size}, age=${now - entry.timestamp}ms")
            entry.markers.forEach { marker ->
                logger.debug("[GUTTER_GET_MARKERS] Cached marker: line=${marker.line}, nodeId=${marker.nodeId}")
            }
            return entry.markers
        }
        
        logger.info("[GUTTER_GET_MARKERS] Step 2: Cache expired or not found, collecting fresh markers for file=$normalizedPath")
        val markers = runBlocking {
            val locator = ServiceLocator(project)
            logger.info("[GUTTER_GET_MARKERS] Step 3: Getting root node from repository...")
            val root = locator.bookmarkRepository.getRootNode()
            logger.info("[GUTTER_GET_MARKERS] Step 4: Root node retrieved, uuid=${root.uuid}")
            logger.info("[GUTTER_GET_MARKERS] Step 5: Collecting markers...")
            collectMarkers(root, normalizedPath)
        }
        logger.info("[GUTTER_GET_MARKERS] Step 6: Collected ${markers.size} markers for file=$normalizedPath")
        markers.forEach { marker ->
            logger.info("[GUTTER_GET_MARKERS] Marker: line=${marker.line}, nodeId=${marker.nodeId}, tooltip=${marker.tooltip}")
        }
        cache[normalizedPath] = CacheEntry(markers, now)
        logger.info("[GUTTER_GET_MARKERS] Step 7: Cache updated")
        return markers
    }

    private fun collectMarkers(root: BookmarkNode, filePath: String): List<MarkerEntry> {
        val markers = mutableListOf<MarkerEntry>()
        val seenLines = mutableSetOf<Int>()
        val normalizedTarget = FileUtil.toSystemIndependentName(filePath)
        logger.debug("collectMarkers: target file=$normalizedTarget")
        
        var bookmarkCount = 0
        var processCount = 0
        
        traverse(root) { node ->
            when (node) {
                is BookmarkNode.Bookmark -> {
                    bookmarkCount++
                    val normalizedNodePath = FileUtil.toSystemIndependentName(node.filePath)
                    logger.debug("collectMarkers: checking bookmark nodeId=${node.uuid}, filePath=$normalizedNodePath, line=${node.line}, match=${normalizedNodePath == normalizedTarget}")
                    if (normalizedNodePath == normalizedTarget && !seenLines.contains(node.line)) {
                        seenLines.add(node.line)
                        markers.add(
                            MarkerEntry(
                                line = node.line,
                                nodeId = node.uuid,
                                icon = bookmarkIcon,
                                tooltip = buildBookmarkTooltip(node)
                            )
                        )
                        logger.info("collectMarkers: added bookmark marker at line ${node.line}, nodeId=${node.uuid}")
                    }
                }
                is BookmarkNode.Process -> {
                    processCount++
                    val entryLine = node.entryLine
                    val normalizedEntryPath = node.entryFilePath?.let { FileUtil.toSystemIndependentName(it) }
                    logger.debug("collectMarkers: checking process nodeId=${node.uuid}, entryFilePath=$normalizedEntryPath, entryLine=$entryLine, match=${normalizedEntryPath == normalizedTarget}")
                    if (normalizedEntryPath == normalizedTarget && entryLine != null && !seenLines.contains(entryLine)) {
                        seenLines.add(entryLine)
                        markers.add(
                            MarkerEntry(
                                line = entryLine,
                                nodeId = node.uuid,
                                icon = processIcon,
                                tooltip = buildProcessTooltip(node)
                            )
                        )
                        logger.info("collectMarkers: added process marker at line $entryLine, nodeId=${node.uuid}")
                    }
                }
                else -> Unit
            }
        }
        
        logger.info("collectMarkers: traversed $bookmarkCount bookmarks and $processCount processes, found ${markers.size} markers for file=$normalizedTarget")
        if (markers.isEmpty()) {
            logger.warn("collectMarkers: no markers found for file=$normalizedTarget")
            return markers
        }
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
        logger.warn("[GUTTER_CLICK] click begin file=${file.path}, line=${marker.line}, nodeId=${marker.nodeId}")
        OpenFileDescriptor(project, file, marker.line, 0).navigate(true)
        ToolWindowManager.getInstance(project).getToolWindow("CodeRemarkTour")?.show(null)
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
