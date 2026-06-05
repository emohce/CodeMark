package emohce.presentation.index

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import emohce.domain.model.BookmarkNode
import emohce.domain.model.childNodes
import emohce.domain.model.searchableText
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

    data class SearchResult(
        val visibleNodeIds: Set<String>,
        val directMatchNodeIds: Set<String>,
        val fullSubtreeRootIds: Set<String>
    ) {
        companion object {
            val EMPTY = SearchResult(emptySet(), emptySet(), emptySet())
        }
    }

    enum class NodeType { BOOKMARK, PROCESS }

    private val byFile = ConcurrentHashMap<String, List<IndexEntry>>()
    private val byNode = ConcurrentHashMap<String, IndexEntry>()
    private val searchTextByNode = ConcurrentHashMap<String, String>()
    private val nameByNode = ConcurrentHashMap<String, String>()
    private val parentByNode = ConcurrentHashMap<String, String>()
    private val childrenByNode = ConcurrentHashMap<String, List<String>>()
    @Volatile private var lastRevision: Long = -1L

    fun rebuild(root: BookmarkNode, revision: Long = -1L) {
        if (revision >= 0 && lastRevision == revision) return
        val fileMap = mutableMapOf<String, MutableList<IndexEntry>>()
        val nodeMap = mutableMapOf<String, IndexEntry>()
        val searchMap = mutableMapOf<String, String>()
        val nameMap = mutableMapOf<String, String>()
        val parentMap = mutableMapOf<String, String>()
        val childrenMap = mutableMapOf<String, List<String>>()
        traverse(root, null) { node, parent ->
            searchMap[node.uuid] = node.searchableText().lowercase()
            nameMap[node.uuid] = node.name.lowercase()
            parent?.let { parentMap[node.uuid] = it.uuid }
            childrenMap[node.uuid] = node.childNodes().map { it.uuid }
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
        searchTextByNode.clear(); searchTextByNode.putAll(searchMap)
        nameByNode.clear(); nameByNode.putAll(nameMap)
        parentByNode.clear(); parentByNode.putAll(parentMap)
        childrenByNode.clear(); childrenByNode.putAll(childrenMap)
        lastRevision = revision
    }

    fun rebuildIfStale(root: BookmarkNode, revision: Long) {
        if (lastRevision != revision) {
            rebuild(root, revision)
        }
    }

    fun findByNodeId(nodeId: String): IndexEntry? = byNode[nodeId]

    fun entriesForFile(filePath: String): List<IndexEntry> = byFile[norm(filePath)].orEmpty()

    fun firstMatch(filePath: String, line: Int?): IndexEntry? {
        val entries = entriesForFile(filePath)
        if (entries.isEmpty()) return null
        if (line == null) return entries.first()
        return entries.minByOrNull { kotlin.math.abs(it.line - line) }
    }

    fun visibleNodeIdsForSearch(query: String): Set<String> {
        return search(query).visibleNodeIds
    }

    fun search(query: String): SearchResult {
        val matched = matchingNodeIdsForSearch(query)
        if (matched.isEmpty()) return SearchResult.EMPTY
        val visible = linkedSetOf<String>()
        val fullSubtreeRoots = linkedSetOf<String>()
        matched.forEach { nodeId ->
            visible.add(nodeId)
            collectAncestors(nodeId, visible)
            if (childrenByNode[nodeId].orEmpty().isNotEmpty()) {
                fullSubtreeRoots.add(nodeId)
                collectDescendants(nodeId, visible)
            }
        }
        return SearchResult(
            visibleNodeIds = visible,
            directMatchNodeIds = matched,
            fullSubtreeRootIds = fullSubtreeRoots
        )
    }

    fun matchingNodeIdsForSearch(query: String): Set<String> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptySet()
        return searchTextByNode.entries
            .asSequence()
            .filter { (_, text) -> text.contains(normalized) }
            .map { it.key }
            .toSet()
    }

    fun matchingNodeIdsByName(query: String): Set<String> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptySet()
        return nameByNode.entries
            .asSequence()
            .filter { (_, name) -> name.contains(normalized) }
            .map { it.key }
            .toSet()
    }

    private fun norm(path: String): String = FileUtil.toSystemIndependentName(path)

    private fun collectAncestors(nodeId: String, result: MutableSet<String>) {
        var current = parentByNode[nodeId]
        while (current != null) {
            result.add(current)
            current = parentByNode[current]
        }
    }

    private fun collectDescendants(nodeId: String, result: MutableSet<String>) {
        childrenByNode[nodeId].orEmpty().forEach { childId ->
            if (result.add(childId)) {
                collectDescendants(childId, result)
            }
        }
    }

    private fun traverse(node: BookmarkNode, parent: BookmarkNode?, visitor: (BookmarkNode, BookmarkNode?) -> Unit) {
        visitor(node, parent)
        node.childNodes().forEach { traverse(it, node, visitor) }
    }

    companion object {
        fun getInstance(project: Project): BookmarkIndexService = project.service()
    }
}
