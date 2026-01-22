package emohce.data.persistence

import kotlinx.serialization.Serializable

@Serializable
data class BookmarkPersistentState(
    val version: Int = CURRENT_VERSION,
    val root: NodeData.GroupData,
    val references: List<ReferenceData> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
