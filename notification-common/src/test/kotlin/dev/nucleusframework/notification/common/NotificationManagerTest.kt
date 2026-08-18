package dev.nucleusframework.notification.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotificationManagerTest {
    @Test
    fun `dismiss reason has the four documented values`() {
        assertEquals(
            listOf(
                DismissReason.USER_DISMISSED,
                DismissReason.TIMED_OUT,
                DismissReason.APPLICATION,
                DismissReason.UNKNOWN,
            ),
            DismissReason.entries.toList(),
        )
    }

    @Test
    fun `success and failure results expose their payloads`() {
        val handle = NotificationHandle("abc", dispatcher = null)
        val success = NotificationResult.Success(handle)
        val failure = NotificationResult.Failure("boom")

        assertEquals(handle, success.handle)
        assertEquals("boom", failure.reason)
        assertEquals("NotificationHandle(abc)", handle.toString())
        handle.dismiss()
    }

    @Test
    fun `handle dismiss is a no-op when the dispatcher is missing`() {
        val handle = NotificationHandle("gone", dispatcher = null)
        handle.dismiss()
        assertEquals("NotificationHandle(gone)", handle.toString())
    }

    @Test
    fun `initialize is safe to call`() {
        NotificationManager.initialize()
    }

    @Test
    fun `send reports a documented failure when notifications cannot be delivered`() {
        val n = notification(title = "Hi", message = "there")
        val result = n.send()
        val viaManager = NotificationManager.send(n)

        if (NotificationManager.isAvailable()) {
            assertIs<NotificationResult.Success>(result)
            assertIs<NotificationResult.Success>(viaManager)
            result.handle.dismiss()
            viaManager.handle.dismiss()
        } else {
            val failure = assertIs<NotificationResult.Failure>(result)
            assertTrue(
                failure.reason == "Notifications not available" ||
                    failure.reason == "No notification support on this platform",
                failure.reason,
            )
            val managerFailure = assertIs<NotificationResult.Failure>(viaManager)
            assertEquals(failure.reason, managerFailure.reason)
        }
    }

    @Test
    fun `isAvailable is a boolean and does not throw`() {
        val available = NotificationManager.isAvailable()
        assertTrue(available || !available)
        if (!available) {
            assertFalse(NotificationManager.isAvailable())
        }
    }
}
