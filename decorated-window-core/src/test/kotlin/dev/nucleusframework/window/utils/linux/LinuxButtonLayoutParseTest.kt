package dev.nucleusframework.window.utils.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxButtonLayoutParseTest {
    @Test
    fun `gnome default close on the right is reversed edge-first`() {
        val layout = LinuxButtonLayout.parse("appmenu:minimize,maximize,close")
        assertTrue(layout.controlsOnRight)
        assertEquals(
            listOf(
                LinuxTitleBarButton.CLOSE,
                LinuxTitleBarButton.MAXIMIZE,
                LinuxTitleBarButton.MINIMIZE,
            ),
            layout.buttons,
        )
        assertTrue(layout.hasClose)
        assertTrue(layout.hasMinimize)
        assertTrue(layout.hasMaximize)
    }

    @Test
    fun `close on the left keeps left-to-right order`() {
        val layout = LinuxButtonLayout.parse("close,minimize,maximize:")
        assertFalse(layout.controlsOnRight)
        assertEquals(
            listOf(
                LinuxTitleBarButton.CLOSE,
                LinuxTitleBarButton.MINIMIZE,
                LinuxTitleBarButton.MAXIMIZE,
            ),
            layout.buttons,
        )
    }

    @Test
    fun `missing colon treats the whole string as the right side`() {
        val layout = LinuxButtonLayout.parse("close")
        assertTrue(layout.controlsOnRight)
        assertEquals(listOf(LinuxTitleBarButton.CLOSE), layout.buttons)
        assertFalse(layout.hasMinimize)
        assertFalse(layout.hasMaximize)
    }

    @Test
    fun `unknown tokens are dropped`() {
        val layout = LinuxButtonLayout.parse("appmenu,spacer:close,unknown,minimize")
        assertTrue(layout.controlsOnRight)
        assertEquals(
            listOf(LinuxTitleBarButton.MINIMIZE, LinuxTitleBarButton.CLOSE),
            layout.buttons,
        )
    }

    @Test
    fun `readSystem falls back to default off gnome`() {
        val system = LinuxButtonLayout.readSystem()
        assertTrue(system.hasClose)
        assertTrue(system.buttons.isNotEmpty())
        if (system == LinuxButtonLayout.Default) {
            assertTrue(system.controlsOnRight)
            assertEquals(3, system.buttons.size)
        }
    }

    @Test
    fun `empty and colon-only layouts keep close on the right`() {
        val empty = LinuxButtonLayout.parse("")
        assertTrue(empty.controlsOnRight)
        assertTrue(empty.buttons.isEmpty())
        assertFalse(empty.hasClose)

        val colon = LinuxButtonLayout.parse(":")
        assertTrue(colon.controlsOnRight)
        assertTrue(colon.buttons.isEmpty())

        val appmenu = LinuxButtonLayout.parse("appmenu:")
        assertTrue(appmenu.controlsOnRight)
        assertTrue(appmenu.buttons.isEmpty())
    }

    @Test
    fun `whitespace around tokens is ignored`() {
        val layout = LinuxButtonLayout.parse("  close , minimize  :")
        assertFalse(layout.controlsOnRight)
        assertEquals(
            listOf(LinuxTitleBarButton.CLOSE, LinuxTitleBarButton.MINIMIZE),
            layout.buttons,
        )
    }

    @Test
    fun `close on the right wins when both sides list it`() {
        val layout = LinuxButtonLayout.parse("close:minimize,close")
        assertTrue(layout.controlsOnRight)
        assertEquals(
            listOf(LinuxTitleBarButton.CLOSE, LinuxTitleBarButton.MINIMIZE),
            layout.buttons,
        )
    }
}
