package emohce.data.mapper

import emohce.data.persistence.NodeData
import emohce.data.persistence.ReferenceData
import emohce.domain.model.BookmarkNode
import emohce.domain.model.Reference
import java.time.Instant

object BookmarkMapper {
    fun toData(node: BookmarkNode): NodeData {
        return when (node) {
            is BookmarkNode.Bookmark -> NodeData.BookmarkData(
                uuid = node.uuid,
                name = node.name,
                description = node.description,
                createdAt = node.createdAt.toEpochMilli(),
                modifiedAt = node.modifiedAt.toEpochMilli(),
                filePath = node.filePath,
                line = node.line,
                column = node.column,
                iconPath = node.iconPath
            )
            is BookmarkNode.DescriptiveBookmark -> NodeData.DescriptiveData(
                uuid = node.uuid,
                name = node.name,
                description = node.description,
                createdAt = node.createdAt.toEpochMilli(),
                modifiedAt = node.modifiedAt.toEpochMilli(),
                markdownContent = node.markdownContent
            )
            is BookmarkNode.Group -> NodeData.GroupData(
                uuid = node.uuid,
                name = node.name,
                description = node.description,
                createdAt = node.createdAt.toEpochMilli(),
                modifiedAt = node.modifiedAt.toEpochMilli(),
                children = node.children.map { toData(it) }
            )
            is BookmarkNode.Process -> NodeData.ProcessData(
                uuid = node.uuid,
                name = node.name,
                description = node.description,
                createdAt = node.createdAt.toEpochMilli(),
                modifiedAt = node.modifiedAt.toEpochMilli(),
                entryFilePath = node.entryFilePath,
                entryLine = node.entryLine,
                markdownContent = node.markdownContent,
                steps = node.steps.map { toData(it) }
            )
        }
    }

    fun fromData(data: NodeData): BookmarkNode {
        return when (data) {
            is NodeData.BookmarkData -> BookmarkNode.Bookmark(
                uuid = data.uuid,
                name = data.name,
                description = data.description,
                createdAt = Instant.ofEpochMilli(data.createdAt),
                modifiedAt = Instant.ofEpochMilli(data.modifiedAt),
                filePath = data.filePath,
                line = data.line,
                column = data.column,
                iconPath = data.iconPath
            )
            is NodeData.DescriptiveData -> BookmarkNode.DescriptiveBookmark(
                uuid = data.uuid,
                name = data.name,
                description = data.description,
                createdAt = Instant.ofEpochMilli(data.createdAt),
                modifiedAt = Instant.ofEpochMilli(data.modifiedAt),
                markdownContent = data.markdownContent
            )
            is NodeData.GroupData -> BookmarkNode.Group(
                uuid = data.uuid,
                name = data.name,
                description = data.description,
                createdAt = Instant.ofEpochMilli(data.createdAt),
                modifiedAt = Instant.ofEpochMilli(data.modifiedAt),
                children = data.children.map { fromData(it) }
            )
            is NodeData.ProcessData -> BookmarkNode.Process(
                uuid = data.uuid,
                name = data.name,
                description = data.description,
                createdAt = Instant.ofEpochMilli(data.createdAt),
                modifiedAt = Instant.ofEpochMilli(data.modifiedAt),
                entryFilePath = data.entryFilePath,
                entryLine = data.entryLine,
                markdownContent = data.markdownContent,
                steps = data.steps.map { fromData(it) }
            )
        }
    }

    fun toReferenceData(reference: Reference): ReferenceData {
        return ReferenceData(
            sourceId = reference.sourceId,
            targetId = reference.targetId,
            createdAt = reference.createdAt.toEpochMilli()
        )
    }

    fun fromReferenceData(data: ReferenceData): Reference {
        return Reference(
            sourceId = data.sourceId,
            targetId = data.targetId,
            createdAt = Instant.ofEpochMilli(data.createdAt)
        )
    }
}
