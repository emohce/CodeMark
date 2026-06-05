package emohce.domain.model

fun BookmarkNode.childNodes(): List<BookmarkNode> {
    return when (this) {
        is BookmarkNode.Group -> children
        is BookmarkNode.Process -> steps
        else -> emptyList()
    }
}

fun BookmarkNode.isContainerNode(): Boolean = childNodes().isNotEmpty() || this is BookmarkNode.Group || this is BookmarkNode.Process

fun BookmarkNode.searchableText(): String {
    return buildString {
        append(name)
        append('\n')
        append(description)
        when (this@searchableText) {
            is BookmarkNode.Bookmark -> {
                append('\n')
                append(filePath)
            }
            is BookmarkNode.DescriptiveBookmark -> {
                append('\n')
                append(markdownContent)
            }
            is BookmarkNode.Process -> {
                append('\n')
                append(entryFilePath.orEmpty())
                append('\n')
                append(markdownContent)
            }
            is BookmarkNode.Group -> Unit
        }
    }
}
