package emohce.domain.model

import java.time.Instant
import java.util.UUID

sealed class BookmarkNode {
    abstract val uuid: String
    abstract val name: String
    abstract val description: String
    abstract val createdAt: Instant
    abstract val modifiedAt: Instant

    data class Bookmark(
        override val uuid: String = UUID.randomUUID().toString(),
        override val name: String,
        override val description: String = "",
        override val createdAt: Instant = Instant.now(),
        override val modifiedAt: Instant = Instant.now(),
        val filePath: String,
        val line: Int,
        val column: Int = 0,
        val iconPath: String? = null
    ) : BookmarkNode()

    data class DescriptiveBookmark(
        override val uuid: String = UUID.randomUUID().toString(),
        override val name: String,
        override val description: String = "",
        override val createdAt: Instant = Instant.now(),
        override val modifiedAt: Instant = Instant.now(),
        val markdownContent: String = ""
    ) : BookmarkNode()

    data class Group(
        override val uuid: String = UUID.randomUUID().toString(),
        override val name: String,
        override val description: String = "",
        override val createdAt: Instant = Instant.now(),
        override val modifiedAt: Instant = Instant.now(),
        val children: List<BookmarkNode> = emptyList()
    ) : BookmarkNode()

    data class Process(
        override val uuid: String = UUID.randomUUID().toString(),
        override val name: String,
        override val description: String = "",
        override val createdAt: Instant = Instant.now(),
        override val modifiedAt: Instant = Instant.now(),
        val entryFilePath: String? = null,
        val entryLine: Int? = null,
        val markdownContent: String = "",
        val steps: List<BookmarkNode> = emptyList()
    ) : BookmarkNode() {
        fun flattenNavigableBookmarks(): List<Bookmark> {
            return steps.flatMap { node ->
                when (node) {
                    is Bookmark -> listOf(node)
                    is Process -> node.flattenNavigableBookmarks()
                    else -> emptyList()
                }
            }
        }
    }
}
