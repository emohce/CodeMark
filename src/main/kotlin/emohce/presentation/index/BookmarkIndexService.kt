package emohce.presentation.index

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import emohce.domain.model.BookmarkNode
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class BookmarkIndexService(private val project: Project) {
    data class IndexEntry(
        val nodeId: String,
        val filePath: String,
        val line: Int,
        val label: String,
        val type: NodeType
    )

    enum class NodeType { BOOKMARK, PROCESS }

    private val byFile = ConcurrentHashMap<String, List<IndexEntry>>()
    private val byNode = ConcurrentHashMap<String, IndexEntry>()

    fun rebuild(root: BookmarkNode) {
        val fileMap = mutableMapOf<String, MutableList<IndexEntry>>()
        val nodeMap = mutableMapOf<String, IndexEntry>()
        traverse(root) { node ->
            when (node) {
                is BookmarkNode.Bookmark -> {
                    val path = norm(node.filePath)
                    val entry = IndexEntry(node.uuid, path, node.line, node.name, NodeType.BOOKMARK)
                    fileMap.getOrPut(path) { mutableListOf() }.add(entry)
                    nodeMap[node.uuid] = entry
                }
                is BookmarkNode.Process -> {
                    val path = node.entryFilePath?.let { norm(it) }
                    val line = node.entryLine
                    if (path != null && line != null) {
                        val entry = IndexEntry(node.uuid, path, line, node.name, NodeType.PROCESS)
                        fileMap.getOrPut(path) { mutableListOf() }.add(entry)
                        nodeMap[node.uuid] = entry
                    }
                }
                else -> Unit
            }
        }
        fileMap.values.forEach { it.sortBy { e -> e.line } }
        byFile.clear(); byFile.putAll(fileMap)
        byNode.clear(); byNode.putAll(nodeMap)
    }

    fun findByNodeId(nodeId: String): IndexEntry? = byNode[nodeId]

    fun entriesForFile(filePath: String): List<IndexEntry> = byFile[norm(filePath)].orEmpty()

    fun firstMatch(filePath: String, line: Int?): IndexEntry? {
        val entries = entriesForFile(filePath)
        if (entries.isEmpty()) return null
        if (line == null) return entries.first()
        return entries.minByOrNull { kotlin.math.abs(it.line - line) }
    }

    private fun norm(path: String): String = FileUtil.toSystemIndependentName(path)

    private fun traverse(node: BookmarkNode, visitor: (BookmarkNode) -> Unit) {
        visitor(node)
        when (node) {
            is BookmarkNode.Group -> node.children.forEach { traverse(it, visitor) }
            is BookmarkNode.Process -> node.steps.forEach { traverse(it, visitor) }
            else -> Unit
        }
    }

    companion object {
        fun getInstance(project: Project): BookmarkIndexService = project.service()
    }
}
