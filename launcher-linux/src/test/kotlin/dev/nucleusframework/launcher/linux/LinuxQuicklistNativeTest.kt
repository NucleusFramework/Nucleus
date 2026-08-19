package dev.nucleusframework.launcher.linux

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxQuicklistNativeTest {
    @Test
    fun `setMenu registers a dbusmenu object and delivers clicks on the edt`() {
        if (!NativeLinuxLauncherBridge.isLoaded) return

        val path = "/dev/nucleusframework/kover/Menu"
        val quicklist = LinuxQuicklist(path)
        val latch = CountDownLatch(1)
        var clicked = -1
        quicklist.listener =
            LinuxQuicklist.Listener { id ->
                clicked = id
                latch.countDown()
            }
        val items =
            listOf(
                DbusmenuItem(id = 1, label = "Open", children = listOf(DbusmenuItem(id = 4, label = "Nested"))),
                DbusmenuItem.separator(id = 2),
                DbusmenuItem(id = 3, label = "Quit"),
            )
        try {
            val registered = quicklist.setMenu(items)
            if (!registered) return
            NativeLinuxLauncherBridge.onMenuItemEvent(path, 3)
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(3, clicked)
            LinuxQuicklist.onItemEvent("/missing", 9)
            assertEquals(3, clicked)
        } finally {
            quicklist.dispose()
            quicklist.dispose()
        }
    }
}
