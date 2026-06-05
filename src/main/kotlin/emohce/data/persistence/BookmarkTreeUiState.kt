package emohce.data.persistence

import kotlinx.serialization.Serializable

/** 书签树 UI 状态（工程级，与 codemark 数据文件分离）。 */
@Serializable
data class BookmarkTreeUiState(
    val version: Int = CURRENT_VERSION,
    val expandedNodeIds: List<String> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
