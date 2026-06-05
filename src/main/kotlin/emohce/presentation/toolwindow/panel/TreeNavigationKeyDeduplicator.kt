package emohce.presentation.toolwindow.panel

import java.awt.event.KeyEvent

internal class TreeNavigationKeyDeduplicator {
    private val duplicateWindowMillis = 20L
    private var lastHandled: KeyPressSignature? = null

    fun shouldHandle(event: KeyEvent): Boolean {
        if (event.id != KeyEvent.KEY_PRESSED) return true
        val signature = KeyPressSignature(
            keyCode = event.keyCode,
            modifiersEx = event.modifiersEx,
            whenMillis = event.`when`
        )
        if (lastHandled?.isDuplicateOf(signature, duplicateWindowMillis) == true) return false
        lastHandled = signature
        return true
    }

    private data class KeyPressSignature(
        val keyCode: Int,
        val modifiersEx: Int,
        val whenMillis: Long
    ) {
        fun isDuplicateOf(other: KeyPressSignature, windowMillis: Long): Boolean {
            return keyCode == other.keyCode &&
                modifiersEx == other.modifiersEx &&
                other.whenMillis - whenMillis in 0..windowMillis
        }
    }
}
