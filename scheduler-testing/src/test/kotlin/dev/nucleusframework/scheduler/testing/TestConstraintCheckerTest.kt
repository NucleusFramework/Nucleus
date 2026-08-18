package dev.nucleusframework.scheduler.testing

import dev.nucleusframework.scheduler.Constraints
import dev.nucleusframework.scheduler.DesktopTask
import dev.nucleusframework.scheduler.DesktopTaskScheduler
import dev.nucleusframework.scheduler.InternalSchedulerApi
import dev.nucleusframework.scheduler.LastTaskResult
import dev.nucleusframework.scheduler.NetworkType
import dev.nucleusframework.scheduler.TaskContext
import dev.nucleusframework.scheduler.TaskId
import dev.nucleusframework.scheduler.TaskRegistry
import dev.nucleusframework.scheduler.TaskRequest
import dev.nucleusframework.scheduler.TaskResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@OptIn(InternalSchedulerApi::class)
class TestConstraintCheckerTest {
    @Test
    fun `defaults satisfy every constraint`() {
        val checker = TestConstraintChecker()
        val result =
            checker.check(
                Constraints(
                    requiredNetworkType = NetworkType.UNMETERED,
                    requiresBatteryNotLow = true,
                    requiresCharging = false,
                    requiresDeviceIdle = false,
                    minimumStorageBytes = 1L,
                ),
            )
        assertTrue(result.satisfied)
        assertTrue(result.unsatisfied.isEmpty())
    }

    @Test
    fun `NOT_REQUIRED network never fails`() {
        TestConstraintChecker().use { checker ->
            checker.networkConnected = false
            checker.networkUnmetered = false
            val result = checker.check(Constraints(requiredNetworkType = NetworkType.NOT_REQUIRED))
            assertTrue(result.satisfied)
        }
    }

    @Test
    fun `CONNECTED fails only when there is no network`() {
        TestConstraintChecker().use { checker ->
            checker.networkConnected = false
            val disconnected = checker.check(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            assertFalse(disconnected.satisfied)
            assertEquals(setOf("network"), disconnected.unsatisfied)

            checker.networkConnected = true
            val connected = checker.check(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            assertTrue(connected.satisfied)
        }
    }

    @Test
    fun `UNMETERED requires both a connection and an unmetered link`() {
        TestConstraintChecker().use { checker ->
            checker.networkConnected = true
            checker.networkUnmetered = false
            val metered = checker.check(Constraints(requiredNetworkType = NetworkType.UNMETERED))
            assertEquals(setOf("network"), metered.unsatisfied)

            checker.networkConnected = false
            checker.networkUnmetered = true
            val offline = checker.check(Constraints(requiredNetworkType = NetworkType.UNMETERED))
            assertEquals(setOf("network"), offline.unsatisfied)
        }
    }

    @Test
    fun `battery constraint ignores a missing battery and fails below 15 percent`() {
        TestConstraintChecker().use { checker ->
            checker.batteryLevel = null
            assertTrue(checker.check(Constraints(requiresBatteryNotLow = true)).satisfied)

            checker.batteryLevel = 0.15f
            assertTrue(checker.check(Constraints(requiresBatteryNotLow = true)).satisfied)

            checker.batteryLevel = 0.149f
            val low = checker.check(Constraints(requiresBatteryNotLow = true))
            assertEquals(setOf("batteryNotLow"), low.unsatisfied)
        }
    }

    @Test
    fun `charging constraint requires the device to be plugged in`() {
        TestConstraintChecker().use { checker ->
            checker.isCharging = false
            assertEquals(setOf("charging"), checker.check(Constraints(requiresCharging = true)).unsatisfied)
            checker.isCharging = true
            assertTrue(checker.check(Constraints(requiresCharging = true)).satisfied)
        }
    }

    @Test
    fun `device idle treats unavailable idle time as satisfied`() {
        TestConstraintChecker().use { checker ->
            checker.idleTimeSeconds = -1
            assertTrue(checker.check(Constraints(requiresDeviceIdle = true)).satisfied)

            checker.idleTimeSeconds = 299
            assertEquals(setOf("deviceIdle"), checker.check(Constraints(requiresDeviceIdle = true)).unsatisfied)

            checker.idleTimeSeconds = 300
            assertTrue(checker.check(Constraints(requiresDeviceIdle = true)).satisfied)
        }
    }

    @Test
    fun `storage constraint compares available bytes`() {
        TestConstraintChecker().use { checker ->
            checker.availableStorageBytes = 99
            val tight = checker.check(Constraints(minimumStorageBytes = 100))
            assertEquals(setOf("storage"), tight.unsatisfied)

            checker.availableStorageBytes = 100
            assertTrue(checker.check(Constraints(minimumStorageBytes = 100)).satisfied)
        }
    }

    @Test
    fun `multiple unsatisfied constraints are collected together`() {
        TestConstraintChecker().use { checker ->
            checker.networkConnected = false
            checker.isCharging = false
            checker.batteryLevel = 0.01f
            checker.idleTimeSeconds = 0
            checker.availableStorageBytes = 0
            val result =
                checker.check(
                    Constraints(
                        requiredNetworkType = NetworkType.CONNECTED,
                        requiresBatteryNotLow = true,
                        requiresCharging = true,
                        requiresDeviceIdle = true,
                        minimumStorageBytes = 1,
                    ),
                )
            assertFalse(result.satisfied)
            assertEquals(
                setOf("network", "batteryNotLow", "charging", "deviceIdle", "storage"),
                result.unsatisfied,
            )
        }
    }

    @Test
    fun `install is co-managed by TestDesktopTaskScheduler`() =
        runBlocking {
            val checker = TestConstraintChecker()
            checker.networkConnected = false
            val taskId = TaskId("constrained")
            val registry =
                TaskRegistry.Builder().register(taskId) {
                    object : DesktopTask {
                        override suspend fun doWork(context: TaskContext): TaskResult = TaskResult.Success
                    }
                }.build()

            TestDesktopTaskScheduler(constraintChecker = checker).use { scheduler ->
                scheduler.install()
                DesktopTaskScheduler.enqueue(
                    TaskRequest.periodic(taskId, 1.hours) {
                        constraints { requiredNetworkType = NetworkType.CONNECTED }
                    },
                )
                assertNull(scheduler.runTask(taskId, registry))
                val history = scheduler.getExecutionHistory(taskId)
                assertEquals(1, history.size)
                assertTrue(history[0].result is LastTaskResult.ConstraintsNotMet)
            }
        }
}
