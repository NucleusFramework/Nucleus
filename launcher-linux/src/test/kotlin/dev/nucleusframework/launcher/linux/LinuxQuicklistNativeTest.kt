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
        // `setMenu` reaches `g_bus_get_sync(G_BUS_TYPE_SESSION, …)`, which has
        // no timeout: on a runner with no session bus it blocks until the job
        // is killed, taking `preMerge` with it (pre-merge.yaml's 30-minute cap
        // exists for exactly this). Nothing to register against without a bus,
        // so skip rather than hang.
        if (!hasSessionBus()) {
            println("SKIPPED: no D-Bus session bus; g_bus_get_sync would block")
            return
        }

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

    /**
     * Whether a session bus is reachable: an explicit address, or the socket
     * GLib falls back to when `DBUS_SESSION_BUS_ADDRESS` is unset.
     */
    private fun hasSessionBus(): Boolean {
        if (!System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) return true
        val runtimeDir = System.getenv("XDG_RUNTIME_DIR") ?: return false
        return java.io.File(runtimeDir, "bus").exists()
    }
}
