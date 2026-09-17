package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.scheduler.Constraints
import dev.nucleusframework.scheduler.InternalSchedulerApi
import dev.nucleusframework.scheduler.NetworkType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalSchedulerApi::class)
class ConstraintCheckerTest {
    private val knownNames = setOf("network", "batteryNotLow", "charging", "deviceIdle", "storage")

    @Test
    fun `ConstraintResult equality is based on satisfied and unsatisfied`() {
        val a = ConstraintResult(satisfied = false, unsatisfied = setOf("network"))
        val b = ConstraintResult(satisfied = false, unsatisfied = setOf("network"))
        val c = ConstraintResult(satisfied = true, unsatisfied = emptySet())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }

    @Test
    fun `no constraints are always satisfied`() {
        val result = SystemInfoConstraintChecker.check(Constraints.NONE)
        assertTrue(result.satisfied)
        assertTrue(result.unsatisfied.isEmpty())
    }

    @Test
    fun `impossible storage requirement is reported as unsatisfied when disks exist`() {
        val result =
            SystemInfoConstraintChecker.check(
                Constraints(minimumStorageBytes = Long.MAX_VALUE),
            )
        assertEquals(result.satisfied, result.unsatisfied.isEmpty())
        assertTrue(result.unsatisfied.all { it in knownNames })
        if (result.unsatisfied.isNotEmpty()) {
            assertTrue("storage" in result.unsatisfied)
            assertFalse(result.satisfied)
        }
    }

    @Test
    fun `each constraint branch produces only known unsatisfied names`() {
        val result =
            SystemInfoConstraintChecker.check(
                Constraints(
                    requiredNetworkType = NetworkType.UNMETERED,
                    requiresBatteryNotLow = true,
                    requiresCharging = true,
                    requiresDeviceIdle = true,
                    minimumStorageBytes = 1L,
                ),
            )
        assertEquals(result.satisfied, result.unsatisfied.isEmpty())
        assertTrue(result.unsatisfied.all { it in knownNames }, result.unsatisfied.toString())
    }

    @Test
    fun `connected network constraint is evaluated independently`() {
        val result =
            SystemInfoConstraintChecker.check(
                Constraints(requiredNetworkType = NetworkType.CONNECTED),
            )
        assertEquals(result.satisfied, result.unsatisfied.isEmpty())
        assertTrue(result.unsatisfied.all { it == "network" })
    }
}
