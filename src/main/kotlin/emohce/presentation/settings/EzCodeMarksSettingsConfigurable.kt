package emohce.presentation.settings

import com.intellij.openapi.options.Configurable
import com.intellij.util.ui.FormBuilder
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

class EzCodeMarksSettingsConfigurable : Configurable {
    private var settingsPanel: JPanel? = null
    private var selectionReferenceHintCheckBox: JCheckBox? = null

    override fun getDisplayName(): String = "EzCodeMarks"

    override fun createComponent(): JComponent {
        val checkBox = JCheckBox("Show shortcut hint when code is selected")
        selectionReferenceHintCheckBox = checkBox
        settingsPanel = FormBuilder.createFormBuilder()
            .addComponent(checkBox)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return settingsPanel!!
    }

    override fun isModified(): Boolean {
        val checkBox = selectionReferenceHintCheckBox ?: return false
        return checkBox.isSelected != EzCodeMarksSettings.getInstance().selectionReferenceHintEnabled
    }

    override fun apply() {
        val checkBox = selectionReferenceHintCheckBox ?: return
        EzCodeMarksSettings.getInstance().selectionReferenceHintEnabled = checkBox.isSelected
    }

    override fun reset() {
        selectionReferenceHintCheckBox?.isSelected = EzCodeMarksSettings.getInstance().selectionReferenceHintEnabled
    }

    override fun disposeUIResources() {
        settingsPanel = null
        selectionReferenceHintCheckBox = null
    }
}
