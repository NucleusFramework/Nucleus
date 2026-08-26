package dev.nucleusframework.notification.common

import dev.nucleusframework.notification.InterruptionLevel
import dev.nucleusframework.notification.linux.Urgency
import dev.nucleusframework.notification.windows.ToastDuration
import dev.nucleusframework.notification.windows.ToastScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationDslTest {
    @Test
    fun `no block leaves all platform scopes unset`() {
        val n = notification(title = "Hi", message = "there")

        assertEquals("Hi", n.title)
        assertNull(n.linux)
        assertNull(n.macos)
        assertNull(n.windows)
    }

    @Test
    fun `buttons DSL still works (backward compatible)`() {
        val n =
            notification(title = "Choose") {
                button("Yes") {}
                button("No") {}
            }

        assertEquals(listOf("Yes", "No"), n.buttons.map { it.title })
        assertNull(n.linux)
    }

    @Test
    fun `platform blocks populate only their own scope`() {
        val n =
            notification(title = "Build failed", message = "see logs") {
                button("Open") {}
                linux {
                    urgency = Urgency.CRITICAL
                    transient = true
                    resident = true
                    category = "device.error"
                    expireTimeout = 0
                }
                macos {
                    interruptionLevel = InterruptionLevel.TIME_SENSITIVE
                    relevanceScore = 0.9
                    subtitle = "CI"
                }
                windows {
                    scenario = ToastScenario.URGENT
                    duration = ToastDuration.LONG
                }
            }

        assertEquals(1, n.buttons.size)

        val linux = requireNotNull(n.linux)
        assertEquals(Urgency.CRITICAL, linux.urgency)
        assertEquals(true, linux.transient)
        assertEquals(true, linux.resident)
        assertEquals("device.error", linux.category)
        assertEquals(0, linux.expireTimeout)

        val macos = requireNotNull(n.macos)
        assertEquals(InterruptionLevel.TIME_SENSITIVE, macos.interruptionLevel)
        assertEquals(0.9, macos.relevanceScore)
        assertEquals("CI", macos.subtitle)

        val windows = requireNotNull(n.windows)
        assertEquals(ToastScenario.URGENT, windows.scenario)
        assertEquals(ToastDuration.LONG, windows.duration)
    }

    @Test
    fun `repeated platform block merges into the same scope`() {
        val n =
            notification(title = "x") {
                linux { urgency = Urgency.LOW }
                linux { transient = true }
            }

        val linux = requireNotNull(n.linux)
        assertEquals(Urgency.LOW, linux.urgency)
        assertTrue(linux.transient == true)
    }

    @Test
    fun `icons callbacks and empty message are stored`() {
        var activated = 0
        var dismissed: DismissReason? = null
        var failed = 0
        val n =
            notification(
                title = "Download",
                message = "",
                largeImage = "file:///tmp/hero.png",
                smallIcon = "file:///tmp/icon.png",
                onActivated = { activated++ },
                onDismissed = { dismissed = it },
                onFailed = { failed++ },
            ) {
                button("Open") { }
            }

        assertEquals("", n.message)
        assertEquals("file:///tmp/hero.png", n.largeImage)
        assertEquals("file:///tmp/icon.png", n.smallIcon)
        n.onActivated?.invoke()
        n.onDismissed?.invoke(DismissReason.TIMED_OUT)
        n.onFailed?.invoke()
        assertEquals(1, activated)
        assertEquals(DismissReason.TIMED_OUT, dismissed)
        assertEquals(1, failed)
    }

    @Test
    fun `empty builder block still leaves scopes unset`() {
        val n = notification(title = "Hi") { }
        assertNull(n.linux)
        assertNull(n.macos)
        assertNull(n.windows)
        assertTrue(n.buttons.isEmpty())
    }

    @Test
    fun `macos and windows blocks merge on repeated calls`() {
        val n =
            notification(title = "x") {
                macos { subtitle = "one" }
                macos { relevanceScore = 0.2 }
                windows { scenario = ToastScenario.ALARM }
                windows { duration = ToastDuration.SHORT }
            }

        val macos = requireNotNull(n.macos)
        assertEquals("one", macos.subtitle)
        assertEquals(0.2, macos.relevanceScore)
        assertNull(macos.interruptionLevel)

        val windows = requireNotNull(n.windows)
        assertEquals(ToastScenario.ALARM, windows.scenario)
        assertEquals(ToastDuration.SHORT, windows.duration)
    }

    @Test
    fun `platform scopes default to unset fields`() {
        val n =
            notification(title = "x") {
                linux { }
                macos { }
                windows { }
            }

        val linux = requireNotNull(n.linux)
        assertNull(linux.urgency)
        assertNull(linux.category)
        assertNull(linux.transient)
        assertNull(linux.resident)
        assertNull(linux.expireTimeout)

        val macos = requireNotNull(n.macos)
        assertNull(macos.interruptionLevel)
        assertNull(macos.relevanceScore)
        assertNull(macos.subtitle)

        val windows = requireNotNull(n.windows)
        assertNull(windows.scenario)
        assertNull(windows.duration)
    }

    @Test
    fun `button onClick handlers are retained`() {
        var clicks = 0
        val n =
            notification(title = "x") {
                button("Go") { clicks++ }
            }
        n.buttons.single().onClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `more than five buttons is rejected`() {
        val error =
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                notification(title = "too many") {
                    repeat(6) { button("B$it") { } }
                }
            }
        assertTrue(error.message!!.contains("Maximum 5 buttons allowed"))
    }

    @Test
    fun `five buttons is accepted`() {
        val n =
            notification(title = "ok") {
                repeat(5) { button("B$it") { } }
            }
        assertEquals(5, n.buttons.size)
        assertEquals((0..4).map { "B$it" }, n.buttons.map { it.title })
    }

    @Test
    fun `linux urgency variants can be selected`() {
        val n =
            notification(title = "u") {
                linux { urgency = Urgency.NORMAL }
            }
        assertEquals(Urgency.NORMAL, n.linux?.urgency)
    }

    @Test
    fun `macos interruption levels can be selected`() {
        val n =
            notification(title = "i") {
                macos { interruptionLevel = InterruptionLevel.CRITICAL }
            }
        assertEquals(InterruptionLevel.CRITICAL, n.macos?.interruptionLevel)
    }

    @Test
    fun `windows reminder and incoming-call scenarios are stored`() {
        val reminder =
            notification(title = "r") {
                windows { scenario = ToastScenario.REMINDER }
            }
        assertEquals(ToastScenario.REMINDER, reminder.windows?.scenario)

        val call =
            notification(title = "c") {
                windows { scenario = ToastScenario.INCOMING_CALL }
            }
        assertEquals(ToastScenario.INCOMING_CALL, call.windows?.scenario)
    }
}
