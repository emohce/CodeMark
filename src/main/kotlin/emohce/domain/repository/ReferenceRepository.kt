package emohce.domain.repository

import emohce.domain.model.Reference

interface ReferenceRepository {
    suspend fun createReference(sourceId: String, targetId: String)
    suspend fun getReferences(sourceId: String): List<Reference>
    suspend fun getAllReferences(): List<Reference>
    suspend fun getReferenceCount(sourceId: String): Int
    suspend fun deleteReference(sourceId: String, targetId: String)
    suspend fun deleteAllReferences(sourceId: String)
    suspend fun deleteAllReferencesForTarget(targetId: String)
    suspend fun syncFromSource(sourceId: String): Int
    suspend fun hasCircularReference(sourceId: String, targetId: String): Boolean
}
