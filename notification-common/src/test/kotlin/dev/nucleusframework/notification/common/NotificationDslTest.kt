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
}
