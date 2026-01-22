package emohce.domain.model

import java.time.Instant

data class Reference(
    val sourceId: String,
    val targetId: String,
    val createdAt: Instant = Instant.now()
)
