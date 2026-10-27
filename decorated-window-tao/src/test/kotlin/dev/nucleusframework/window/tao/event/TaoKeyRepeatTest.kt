package dev.nucleusframework.window.tao.event

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.nucleusframework.window.tao.TaoEventCode
import dev.nucleusframework.window.tao.TaoKeyLocation
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.isRepeat
import java.awt.event.KeyEvent.VK_W
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A held key's auto-repeat, from the wire flag to Compose's [KeyEvent.isRepeat]. */
class TaoKeyRepeatTest {
    @Test
    fun `a repeated key-down is a repeat`() {
        assertTrue(keyEvent(keyDown = true, isRepeat = true).isRepeat)
    }

    @Test
    fun `a fresh key-down is not a repeat`() {
        assertFalse(keyEvent(keyDown = true, isRepeat = false).isRepeat)
    }

    @Test
    fun `a key-up is never a repeat`() {
        assertFalse(keyEvent(keyDown = false, isRepeat = true).isRepeat)
    }

    @Test
    fun `typed text is never a repeat`() {
        val typed = taoTypedKeyEvent('w'.code, TaoKeyLocation.STANDARD, false, false, false, false)
        assertFalse(typed.isRepeat)
    }

    @OptIn(InternalComposeUiApi::class)
    @Test
    fun `an event Nucleus did not produce is not a repeat`() {
        val foreign = KeyEvent(Key.W, KeyEventType.KeyDown)
        assertFalse(foreign.isRepeat)
    }

    @Test
    fun `a repeat keeps the key and type of a plain key-down`() {
        val repeat = keyEvent(keyDown = true, isRepeat = true)
        val fresh = keyEvent(keyDown = true, isRepeat = false)
        assertEquals(fresh.key, repeat.key)
        assertEquals(fresh.type, repeat.type)
        assertEquals(fresh.isCtrlPressed, repeat.isCtrlPressed)
    }

    @Test
    fun `a key listener that ignores the flag still hears repeats`() {
        val heard = mutableListOf<Int>()
        val listener = TaoWindow.KeyEventListener { type, _, _, _, _ -> heard += type }
        listener.onKey(TaoEventCode.KEY_DOWN, VK_W, TaoKeyLocation.STANDARD, 0, 'w'.code, isRepeat = true)
        assertEquals(listOf(TaoEventCode.KEY_DOWN), heard)
    }

    private fun keyEvent(
        keyDown: Boolean,
        isRepeat: Boolean,
    ) = taoKeyEvent(
        keyDown = keyDown,
        vkCode = VK_W,
        keyLocation = TaoKeyLocation.STANDARD,
        isShift = false,
        isCtrl = true,
        isAlt = false,
        isMeta = false,
        codePoint = 'w'.code,
        isRepeat = isRepeat,
    )
}
