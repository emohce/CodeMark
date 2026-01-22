package emohce.domain.usecase.reference

import emohce.domain.repository.ReferenceRepository

class DetectCircularRefUseCase(
    private val referenceRepository: ReferenceRepository
) {
    suspend fun execute(sourceId: String, targetId: String): Boolean {
        return referenceRepository.hasCircularReference(sourceId, targetId)
    }
}
