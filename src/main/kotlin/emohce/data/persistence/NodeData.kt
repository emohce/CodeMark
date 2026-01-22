package emohce.data.persistence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class NodeData {
    abstract val uuid: String
    abstract val name: String
    abstract val description: String
    abstract val createdAt: Long
    abstract val modifiedAt: Long

    @Serializable
    @SerialName("bookmark")
    data class BookmarkData(
        override val uuid: String,
        override val name: String,
        override val description: String = "",
        override val createdAt: Long = System.currentTimeMillis(),
        override val modifiedAt: Long = System.currentTimeMillis(),
        val filePath: String,
        val line: Int,
        val column: Int = 0,
        val iconPath: String? = null
    ) : NodeData()

    @Serializable
    @SerialName("descriptive")
    data class DescriptiveData(
        override val uuid: String,
        override val name: String,
        override val description: String = "",
        override val createdAt: Long = System.currentTimeMillis(),
        override val modifiedAt: Long = System.currentTimeMillis(),
        val markdownContent: String = ""
    ) : NodeData()

    @Serializable
    @SerialName("group")
    data class GroupData(
        override val uuid: String,
        override val name: String,
        override val description: String = "",
        override val createdAt: Long = System.currentTimeMillis(),
        override val modifiedAt: Long = System.currentTimeMillis(),
        val children: List<NodeData> = emptyList()
    ) : NodeData()

    @Serializable
    @SerialName("process")
    data class ProcessData(
        override val uuid: String,
        override val name: String,
        override val description: String = "",
        override val createdAt: Long = System.currentTimeMillis(),
        override val modifiedAt: Long = System.currentTimeMillis(),
        val entryFilePath: String? = null,
        val entryLine: Int? = null,
        val markdownContent: String = "",
        val steps: List<NodeData> = emptyList()
    ) : NodeData()
}
