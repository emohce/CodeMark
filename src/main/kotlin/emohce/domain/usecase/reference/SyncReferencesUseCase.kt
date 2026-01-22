package emohce.domain.usecase.reference

import emohce.domain.model.BookmarkNode
import emohce.domain.repository.BookmarkRepository
import emohce.domain.repository.ReferenceRepository
import java.time.Instant

class SyncReferencesUseCase(
    private val bookmarkRepository: BookmarkRepository,
    private val referenceRepository: ReferenceRepository
) {
    suspend fun execute(sourceId: String): SyncResult {
        val source = bookmarkRepository.findByUuid(sourceId) as? BookmarkNode.Bookmark
            ?: return SyncResult.SourceNotFound

        val references = referenceRepository.getReferences(sourceId)
        if (references.isEmpty()) {
            return SyncResult.NoReferences
        }

        var syncedCount = 0
        val errors = mutableListOf<String>()

        references.forEach { ref ->
            try {
                val target = bookmarkRepository.findByUuid(ref.targetId) as? BookmarkNode.Bookmark
                    ?: return@forEach

                val updated = target.copy(
                    name = source.name,
                    description = source.description,
                    filePath = source.filePath,
                    line = source.line,
                    column = source.column,
                    iconPath = source.iconPath,
                    modifiedAt = Instant.now()
                )
                bookmarkRepository.update(updated)
                syncedCount++
            } catch (e: Exception) {
                errors.add("${ref.targetId}: ${e.message}")
            }
        }

        return SyncResult.Success(syncedCount, errors)
    }

    sealed class SyncResult {
        data object SourceNotFound : SyncResult()
        data object NoReferences : SyncResult()
        data class Success(val count: Int, val errors: List<String>) : SyncResult()
    }
}
