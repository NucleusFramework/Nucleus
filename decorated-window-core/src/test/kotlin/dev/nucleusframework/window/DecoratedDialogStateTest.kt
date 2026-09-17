package dev.nucleusframework.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecoratedDialogStateTest {
    @Test
    fun `dialog state only tracks the active bit`() {
        val active = DecoratedDialogState.of(active = true)
        val inactive = DecoratedDialogState.of(active = false)
        assertTrue(active.isActive)
        assertFalse(inactive.isActive)
        assertTrue(active.copy(active = false).let { !it.isActive && it.copy(active = true).isActive })
        val asWindow = active.toDecoratedWindowState()
        assertTrue(asWindow.isActive)
        assertFalse(asWindow.isFullscreen)
        assertFalse(asWindow.isMinimized)
        assertFalse(asWindow.isMaximized)
        assertTrue(asWindow.isResizable)
        assertTrue(active.toString().contains("isActive=true"))
        assertEquals(DecoratedDialogState.Active, 1UL)
        val info = DialogTitleBarInfo("Hello", null)
        assertEquals("Hello", info.title)
        assertEquals(null, info.icon)
    }
}
