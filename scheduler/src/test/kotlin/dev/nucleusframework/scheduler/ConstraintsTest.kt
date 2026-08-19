package dev.nucleusframework.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class ConstraintsTest {
    @Test
    fun `NONE has no requirements`() {
        val none = Constraints.NONE
        assertEquals(NetworkType.NOT_REQUIRED, none.requiredNetworkType)
        assertFalse(none.requiresBatteryNotLow)
        assertFalse(none.requiresCharging)
        assertFalse(none.requiresDeviceIdle)
        assertNull(none.minimumStorageBytes)
        assertFalse(none.hasConstraints())
    }

    @Test
    fun `any single field makes hasConstraints true`() {
        assertTrue(Constraints(requiredNetworkType = NetworkType.CONNECTED).hasConstraints())
        assertTrue(Constraints(requiresBatteryNotLow = true).hasConstraints())
        assertTrue(Constraints(requiresCharging = true).hasConstraints())
        assertTrue(Constraints(requiresDeviceIdle = true).hasConstraints())
        assertTrue(Constraints(minimumStorageBytes = 1L).hasConstraints())
    }

    @Test
    fun `data class equality distinguishes fields`() {
        val a = Constraints(requiresCharging = true)
        val b = Constraints(requiresCharging = true)
        val c = Constraints(requiresDeviceIdle = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `NetworkType exposes the three documented values`() {
        assertEquals(
            listOf(NetworkType.NOT_REQUIRED, NetworkType.CONNECTED, NetworkType.UNMETERED),
            NetworkType.entries,
        )
    }

    @Test
    fun `TaskRequest constraints DSL builds the same object as the data class`() {
        val request =
            TaskRequest.periodic(TaskId("sync"), 1.hours) {
                constraints {
                    requiredNetworkType = NetworkType.UNMETERED
                    requiresBatteryNotLow = true
                    requiresCharging = true
                    requiresDeviceIdle = true
                    minimumStorageBytes = 50_000_000L
                }
            }

        assertEquals(
            Constraints(
                requiredNetworkType = NetworkType.UNMETERED,
                requiresBatteryNotLow = true,
                requiresCharging = true,
                requiresDeviceIdle = true,
                minimumStorageBytes = 50_000_000L,
            ),
            request.constraints,
        )
        assertTrue(request.constraints.hasConstraints())
    }

    @Test
    fun `TaskRequest constraints setter accepts a prebuilt instance`() {
        val constraints = Constraints(requiredNetworkType = NetworkType.CONNECTED)
        val request =
            TaskRequest.onBoot(TaskId("boot")) {
                constraints(constraints)
            }
        assertEquals(constraints, request.constraints)
    }
}
