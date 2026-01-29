package emohce.presentation.selection

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class SelectionRequest(val nodeId: String, val filePath: String? = null, val line: Int? = null)

@Service(Service.Level.PROJECT)
class SelectionBus {
    private val _requests = MutableSharedFlow<SelectionRequest>(extraBufferCapacity = 64)
    val requests: SharedFlow<SelectionRequest> = _requests.asSharedFlow()
    @Volatile
    private var currentContainerId: String? = null
    @Volatile
    private var lastSelectedNodeId: String? = null

    fun requestSelect(nodeId: String, filePath: String? = null, line: Int? = null) {
        _requests.tryEmit(SelectionRequest(nodeId, filePath, line))
    }

    fun setCurrentContainerId(containerId: String?) {
        currentContainerId = containerId
    }

    fun setLastSelectedNodeId(nodeId: String?) {
        lastSelectedNodeId = nodeId
    }

    fun getCurrentContainerId(): String? = currentContainerId
    fun getLastSelectedNodeId(): String? = lastSelectedNodeId

    companion object {
        fun getInstance(project: Project): SelectionBus = project.service()
    }
}
