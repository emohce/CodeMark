package emohce.presentation.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

data class EzCodeMarksSettingsState(
    var selectionReferenceHintEnabled: Boolean = true
)

@Service(Service.Level.APP)
@State(
    name = "EzCodeMarksSettings",
    storages = [Storage("ezCodeMarks.xml")]
)
class EzCodeMarksSettings : PersistentStateComponent<EzCodeMarksSettingsState> {
    private var state = EzCodeMarksSettingsState()

    override fun getState(): EzCodeMarksSettingsState = state

    override fun loadState(state: EzCodeMarksSettingsState) {
        this.state = state
    }

    var selectionReferenceHintEnabled: Boolean
        get() = state.selectionReferenceHintEnabled
        set(value) {
            state.selectionReferenceHintEnabled = value
        }

    companion object {
        fun getInstance(): EzCodeMarksSettings =
            ApplicationManager.getApplication().getService(EzCodeMarksSettings::class.java)
    }
}
