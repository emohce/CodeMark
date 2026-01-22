package emohce.data.repository

import emohce.domain.model.Reference
import emohce.domain.repository.ReferenceRepository

class ReferenceRepositoryImpl(private val store: BookmarkStore) : ReferenceRepository {
    override suspend fun createReference(sourceId: String, targetId: String) {
        if (store.references.any { it.sourceId == sourceId && it.targetId == targetId }) return
        store.references.add(Reference(sourceId = sourceId, targetId = targetId))
        store.save()
    }

    override suspend fun getReferences(sourceId: String): List<Reference> {
        return store.references.filter { it.sourceId == sourceId }
    }

    override suspend fun getAllReferences(): List<Reference> {
        return store.references.toList()
    }

    override suspend fun getReferenceCount(sourceId: String): Int {
        return store.references.count { it.sourceId == sourceId }
    }

    override suspend fun deleteReference(sourceId: String, targetId: String) {
        store.references.removeIf { it.sourceId == sourceId && it.targetId == targetId }
        store.save()
    }

    override suspend fun deleteAllReferences(sourceId: String) {
        store.references.removeIf { it.sourceId == sourceId }
        store.save()
    }

    override suspend fun deleteAllReferencesForTarget(targetId: String) {
        store.references.removeIf { it.targetId == targetId }
        store.save()
    }

    override suspend fun syncFromSource(sourceId: String): Int {
        return store.references.count { it.sourceId == sourceId }
    }

    override suspend fun hasCircularReference(sourceId: String, targetId: String): Boolean {
        val visited = mutableSetOf<String>()
        return hasCycle(sourceId, targetId, visited)
    }

    private fun hasCycle(sourceId: String, currentId: String, visited: MutableSet<String>): Boolean {
        if (currentId == sourceId) return true
        if (!visited.add(currentId)) return true

        val next = store.references.filter { it.sourceId == currentId }.map { it.targetId }
        return next.any { hasCycle(sourceId, it, visited) }
    }
}
