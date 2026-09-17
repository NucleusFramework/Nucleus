package dev.nucleusframework.notification.windows

import dev.nucleusframework.core.runtime.Platform
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsNotificationNativeTest {
    @AfterTest
    fun tearDown() {
        if (WindowsNotificationCenter.isAvailable) {
            WindowsNotificationCenter.clearAll()
            WindowsNotificationCenter.uninitialize()
        }
    }

    @Test
    fun `center initializes and can show then remove a toast`() {
        if (Platform.Current != Platform.Windows) return
        assertTrue(WindowsNotificationCenter.isAvailable, "nucleus_notification_windows must load on Windows")

        val initialized =
            WindowsNotificationCenter.initialize(
                aumid = "dev.nucleusframework.kover",
                appName = "NucleusKover",
                shortcutPolicy = ShortcutPolicy.IGNORE,
            )
        assertTrue(initialized, "initialize(IGNORE) must succeed when the native library is loaded")

        val error = AtomicReference<String?>("unset")
        val shown = CountDownLatch(1)
        WindowsNotificationCenter.show(
            toast { visual { text("kover") } },
            tag = "kover",
            group = "kover",
            suppressPopup = true,
        ) { err ->
            error.set(err)
            shown.countDown()
        }
        assertTrue(shown.await(5, TimeUnit.SECONDS), "show callback must run")
        assertEquals(null, error.get(), "show must succeed after initialize: ${error.get()}")

        WindowsNotificationCenter.remove("kover", "kover")
        WindowsNotificationCenter.removeGroup("kover")
        WindowsNotificationCenter.clearAll()

        val history = AtomicReference<List<HistoryEntry>?>(null)
        val historyError = AtomicReference<String?>("unset")
        val historyLatch = CountDownLatch(1)
        WindowsNotificationCenter.getHistory { entries, err ->
            history.set(entries)
            historyError.set(err)
            historyLatch.countDown()
        }
        assertTrue(historyLatch.await(5, TimeUnit.SECONDS))
        assertEquals(null, historyError.get(), "getHistory: ${historyError.get()}")
        assertTrue(history.get() != null)
    }
}
