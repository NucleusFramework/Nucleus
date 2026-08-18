package dev.nucleusframework.notification.windows

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsNotificationCenterTest {
    @Test
    fun `unavailable center reports the documented fallback`() {
        assertFalse(WindowsNotificationCenter.isAvailable)
        assertFalse(WindowsNotificationCenter.initialize(aumid = "com.example.Test"))
        assertFalse(WindowsNotificationCenter.initialize(aumid = "com.example.Test", appName = "Test"))

        var showError: String? = "unset"
        WindowsNotificationCenter.show(
            toast { visual { text("Hi") } },
            tag = "tag",
            group = "grp",
            initialData = ToastNotificationData(1, mapOf("k" to "v")),
        ) { showError = it }
        assertEquals("Not available on this platform", showError)

        var xmlError: String? = "unset"
        WindowsNotificationCenter.showFromXml("<toast/>") { xmlError = it }
        assertEquals("Not available on this platform", xmlError)

        var simpleError: String? = "unset"
        WindowsNotificationCenter.showSimple("Title", "Body", "Body2", tag = "t") { simpleError = it }
        assertEquals("Not available on this platform", simpleError)

        var updateError: String? = "unset"
        WindowsNotificationCenter.update("tag", "grp", ToastNotificationData(2, mapOf("a" to "b"))) {
            updateError = it
        }
        assertEquals("Not available on this platform", updateError)

        WindowsNotificationCenter.show(toast { visual { text("No callback") } })
        WindowsNotificationCenter.showFromXml("<toast/>")
        WindowsNotificationCenter.showSimple("Title")
        WindowsNotificationCenter.remove("tag", "grp")
        WindowsNotificationCenter.removeGroup("grp")
        WindowsNotificationCenter.clearAll()
        WindowsNotificationCenter.uninitialize()

        var historyError: String? = null
        var history: List<HistoryEntry>? = null
        WindowsNotificationCenter.getHistory { entries, error ->
            history = entries
            historyError = error
        }
        assertEquals(emptyList(), history)
        assertEquals("Not available", historyError)
    }

    @Test
    fun `listeners can be added and removed without a native backend`() {
        val listener =
            object : ToastNotificationListener {
                override fun onActivated(
                    tag: String,
                    group: String,
                    arguments: String,
                    userInputs: Map<String, String>,
                ) = Unit

                override fun onDismissed(
                    tag: String,
                    group: String,
                    reason: DismissalReason,
                ) = Unit

                override fun onFailed(
                    tag: String,
                    group: String,
                    errorCode: Int,
                ) = Unit
            }
        WindowsNotificationCenter.addListener(listener)
        WindowsNotificationCenter.removeListener(listener)
    }

    @Test
    fun `bridge callbacks deliver shown updated history and listener events`() {
        var shown: String? = "unset"
        val shownId = NativeWindowsNotificationBridge.registerCallback<(String?) -> Unit> { shown = it }
        NativeWindowsNotificationBridge.onToastShown(shownId, "show-failed")
        assertEquals("show-failed", shown)
        NativeWindowsNotificationBridge.onToastShown(999_999, "ignored")

        var updated: String? = "unset"
        val updatedId = NativeWindowsNotificationBridge.registerCallback<(String?) -> Unit> { updated = it }
        NativeWindowsNotificationBridge.onToastUpdated(updatedId, null)
        assertNull(updated)

        var history: List<HistoryEntry>? = null
        var historyError: String? = "unset"
        val historyId =
            NativeWindowsNotificationBridge.registerCallback<(List<HistoryEntry>, String?) -> Unit> { entries, error ->
                history = entries
                historyError = error
            }
        NativeWindowsNotificationBridge.onHistoryResult(
            historyId,
            arrayOf("t1", "t2"),
            arrayOf("g1", "g2"),
            null,
        )
        assertEquals(listOf(HistoryEntry("t1", "g1"), HistoryEntry("t2", "g2")), history)
        assertNull(historyError)

        val latch = CountDownLatch(3)
        var activatedArgs: String? = null
        var activatedInputs: Map<String, String> = emptyMap()
        var dismissed: DismissalReason? = null
        var failedCode: Int? = null
        val listener =
            object : ToastNotificationListener {
                override fun onActivated(
                    tag: String,
                    group: String,
                    arguments: String,
                    userInputs: Map<String, String>,
                ) {
                    activatedArgs = arguments
                    activatedInputs = userInputs
                    latch.countDown()
                }

                override fun onDismissed(
                    tag: String,
                    group: String,
                    reason: DismissalReason,
                ) {
                    dismissed = reason
                    latch.countDown()
                }

                override fun onFailed(
                    tag: String,
                    group: String,
                    errorCode: Int,
                ) {
                    failedCode = errorCode
                    latch.countDown()
                }
            }
        NativeWindowsNotificationBridge.addListener(listener)
        try {
            NativeWindowsNotificationBridge.onToastActivated(
                "tag",
                "grp",
                "action=open",
                arrayOf("box"),
                arrayOf("hello"),
            )
            NativeWindowsNotificationBridge.onToastDismissed("tag", "grp", 0)
            NativeWindowsNotificationBridge.onToastFailed("tag", "grp", 42)
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals("action=open", activatedArgs)
            assertEquals(mapOf("box" to "hello"), activatedInputs)
            assertEquals(DismissalReason.USER_CANCELED, dismissed)
            assertEquals(42, failedCode)
        } finally {
            NativeWindowsNotificationBridge.removeListener(listener)
        }
    }

    @Test
    fun `shortcut policy and history models keep their values`() {
        assertEquals(0, ShortcutPolicy.IGNORE.nativeValue)
        assertEquals(1, ShortcutPolicy.REQUIRE_NO_CREATE.nativeValue)
        assertEquals(2, ShortcutPolicy.REQUIRE_CREATE.nativeValue)
        val entry = HistoryEntry("tag", "group")
        assertEquals("tag", entry.tag)
        assertEquals("group", entry.group)
        val data = ToastNotificationData(3, mapOf("progressValue" to "0.4"))
        assertEquals(3, data.sequenceNumber)
        assertEquals("0.4", data.values["progressValue"])
        val activated = ToastActivatedEventArgs("a", mapOf("k" to "v"))
        assertEquals("a", activated.arguments)
        assertEquals(mapOf("k" to "v"), activated.userInputs)
        assertEquals(DismissalReason.TIMED_OUT, ToastDismissedEventArgs(DismissalReason.TIMED_OUT).reason)
        assertEquals(7, ToastFailedEventArgs(7).errorCode)
    }

    @Test
    fun `system snooze and dismiss button models retain custom labels`() {
        val snooze = ToastButtonSnooze(customContent = "Later", selectionBoxId = "times")
        assertEquals("Later", snooze.customContent)
        assertEquals("times", snooze.selectionBoxId)
        val dismiss = ToastButtonDismiss("Close")
        assertEquals("Close", dismiss.customContent)
    }

    @Test
    fun `show simple builds one two or three text lines`() {
        var error: String? = null
        WindowsNotificationCenter.showSimple("Only title") { error = it }
        assertEquals("Not available on this platform", error)
        WindowsNotificationCenter.showSimple("Title", "Body") { error = it }
        WindowsNotificationCenter.showSimple("Title", "Body", "Line2") { error = it }
        assertEquals("Not available on this platform", error)
    }
}
