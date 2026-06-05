package emohce.data.persistence

import kotlinx.serialization.Serializable

@Serializable
data class BookmarkPersistentState(
    val version: Int = CURRENT_VERSION,
    val root: NodeData.GroupData? = null,
    val references: List<ReferenceData> = emptyList(),
    val nodes: Map<String, NodeData> = emptyMap(),
    val children: Map<String, List<String>> = emptyMap(),
    val roots: List<String> = emptyList()
) {
    fun rootData(): NodeData.GroupData? {
        root?.let { return it }
        val rootId = roots.firstOrNull() ?: return null
        return buildNode(rootId) as? NodeData.GroupData
    }

    private fun buildNode(nodeId: String): NodeData? {
        val node = nodes[nodeId] ?: return null
        val childNodes = children[nodeId].orEmpty().mapNotNull { buildNode(it) }
        return when (node) {
            is NodeData.GroupData -> node.copy(children = childNodes)
            is NodeData.ProcessData -> node.copy(steps = childNodes)
            is NodeData.BookmarkData -> node
            is NodeData.DescriptiveData -> node
        }
    }

    companion object {
        const val CURRENT_VERSION = 2

        fun fromRoot(
            root: NodeData.GroupData,
            references: List<ReferenceData> = emptyList()
        ): BookmarkPersistentState {
            val flatNodes = linkedMapOf<String, NodeData>()
            val flatChildren = linkedMapOf<String, List<String>>()

            fun visit(node: NodeData) {
                when (node) {
                    is NodeData.GroupData -> {
                        flatNodes[node.uuid] = node.copy(children = emptyList())
                        flatChildren[node.uuid] = node.children.map { it.uuid }
                        node.children.forEach { visit(it) }
                    }
                    is NodeData.ProcessData -> {
                        flatNodes[node.uuid] = node.copy(steps = emptyList())
                        flatChildren[node.uuid] = node.steps.map { it.uuid }
                        node.steps.forEach { visit(it) }
                    }
                    is NodeData.BookmarkData -> {
                        flatNodes[node.uuid] = node
                    }
                    is NodeData.DescriptiveData -> {
                        flatNodes[node.uuid] = node
                    }
                }
            }

            visit(root)
            return BookmarkPersistentState(
                version = CURRENT_VERSION,
                root = null,
                references = references,
                nodes = flatNodes,
                children = flatChildren,
                roots = listOf(root.uuid)
            )
        }
    }
}
