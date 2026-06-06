package emohce.presentation.settings

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EzCodeMarksSettingsStateTest {
    @Test
    fun `selection reference hint is enabled by default`() {
        assertTrue(EzCodeMarksSettingsState().selectionReferenceHintEnabled)
    }
}
