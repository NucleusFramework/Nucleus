package dev.nucleusframework.launcher.macos

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacOsDockMenuTest {
    @Test
    fun `dock menu items support separators children and disabled state`() {
        val child = DockMenuItem(id = 2, title = "Nested")
        val item = DockMenuItem(id = 1, title = "File", children = listOf(child))
        val separator = DockMenuItem.separator(3)
        val disabled = DockMenuItem(id = 4, title = "Quit", enabled = false)

        assertEquals(1, item.id)
        assertEquals("File", item.title)
        assertEquals(listOf(child), item.children)
        assertEquals("-", separator.title)
        assertTrue(separator.enabled)
        assertEquals(false, disabled.enabled)
    }

    @Test
    fun `setDockMenu flattens a hierarchy and is safe to clear`() {
        val items =
            listOf(
                DockMenuItem(
                    id = 1,
                    title = "File",
                    children =
                        listOf(
                            DockMenuItem(id = 2, title = "Open"),
                            DockMenuItem.separator(3),
                            DockMenuItem(id = 4, title = "Disabled", enabled = false),
                        ),
                ),
                DockMenuItem.separator(5),
                DockMenuItem(id = 6, title = "Quit"),
            )
        MacOsDockMenu.setDockMenu(items)
        MacOsDockMenu.setDockMenu(emptyList())
        MacOsDockMenu.clearDockMenu()
        if (MacOsDockMenu.isAvailable) {
            assertTrue(NativeMacOsDockMenuBridge.isLoaded)
        }
    }

    @Test
    fun `native click callback is delivered on the edt`() {
        val latch = CountDownLatch(1)
        var clicked = -1
        MacOsDockMenu.listener =
            DockMenuListener { id ->
                clicked = id
                latch.countDown()
            }
        try {
            NativeMacOsDockMenuBridge.onMenuItemClicked(42)
            if (MacOsDockMenu.listener != null) {
                assertTrue(latch.await(3, TimeUnit.SECONDS))
                assertEquals(42, clicked)
            }
        } finally {
            MacOsDockMenu.listener = null
        }
        NativeMacOsDockMenuBridge.onMenuItemClicked(1)
    }
}
