package dev.nucleusframework.notification.linux

import kotlin.test.Test
import kotlin.test.assertTrue

class LinuxNotificationNativeTest {
    @Test
    fun `center queries the session notification server when native is loaded`() {
        if (!LinuxNotificationCenter.isAvailable) return

        val capabilities = LinuxNotificationCenter.getCapabilities()
        val info = LinuxNotificationCenter.getServerInformation()
        if (info != null) {
            assertTrue(info.name.isNotBlank() || info.vendor.isNotBlank() || info.version.isNotBlank())
        }
        if (capabilities.isNotEmpty()) {
            assertTrue(capabilities.all { it.isNotBlank() })
        }

        val id =
            LinuxNotificationCenter.notify(
                Notification(
                    appName = "NucleusCoverage",
                    summary = "kover coverage",
                    body = "transient",
                    expireTimeout = 1,
                ),
            )
        if (id > 0) {
            LinuxNotificationCenter.closeNotification(id)
        }
    }
}
