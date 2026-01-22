package emohce.data.persistence

import kotlinx.serialization.Serializable

@Serializable
data class ReferenceData(
    val sourceId: String,
    val targetId: String,
    val createdAt: Long = System.currentTimeMillis()
)
