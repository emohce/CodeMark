package emohce.presentation.selection

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Service(Service.Level.PROJECT)
class SelectionBus {
    private val _requests = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val requests: SharedFlow<String> = _requests.asSharedFlow()
    @Volatile
    private var currentContainerId: String? = null

    fun requestSelect(nodeId: String) {
        _requests.tryEmit(nodeId)
    }

    fun setCurrentContainerId(containerId: String?) {
        currentContainerId = containerId
    }

    fun getCurrentContainerId(): String? = currentContainerId

    companion object {
        fun getInstance(project: Project): SelectionBus = project.service()
    }
}
