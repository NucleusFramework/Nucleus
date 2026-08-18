package dev.nucleusframework.scheduler

import kotlinx.serialization.Serializable
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(InternalSchedulerApi::class)
class TaskRequestTest {
    @Serializable
    private data class SyncInput(
        val endpoint: String,
        val retries: Int,
    )

    @Test
    fun `periodic rejects intervals below 15 minutes`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                TaskRequest.periodic(TaskId("too-fast"), 14.minutes)
            }
        assertTrue(error.message!!.contains("15m"))
        assertTrue(error.message!!.contains("14m"))
    }

    @Test
    fun `periodic accepts the 15 minute minimum`() {
        val request = TaskRequest.periodic(TaskId("ok"), 15.minutes)
        assertEquals(TaskRequest.Type.PERIODIC, request.type)
        assertEquals(15.minutes, request.interval)
        assertNull(request.cronExpression)
        assertEquals(TaskData.EMPTY, request.inputData)
        assertNull(request.retryPolicy)
        assertEquals(ExistingTaskPolicy.KEEP, request.existingTaskPolicy)
        assertFalse(request.runImmediately)
        assertEquals(Constraints.NONE, request.constraints)
    }

    @Test
    fun `periodic builder configures every optional field`() {
        val policy = RetryPolicy.Linear(delay = 20.minutes, maxAttempts = 4)
        val request =
            TaskRequest.periodic(TaskId("sync"), 2.hours) {
                inputData(SyncInput(endpoint = "https://api.example.com", retries = 2))
                retryPolicy(policy)
                existingTaskPolicy(ExistingTaskPolicy.REPLACE)
                runImmediately()
                constraints {
                    requiredNetworkType = NetworkType.CONNECTED
                }
            }

        assertEquals(TaskId("sync"), request.taskId)
        assertEquals(2.hours, request.interval)
        assertEquals(SyncInput(endpoint = "https://api.example.com", retries = 2), request.inputData.decode<SyncInput>())
        assertEquals(policy, request.retryPolicy)
        assertEquals(ExistingTaskPolicy.REPLACE, request.existingTaskPolicy)
        assertTrue(request.runImmediately)
        assertEquals(NetworkType.CONNECTED, request.constraints.requiredNetworkType)
    }

    @Test
    fun `periodic inputData can take an explicit serializer`() {
        val request =
            TaskRequest.periodic(TaskId("sync"), 1.hours) {
                inputData(SyncInput(endpoint = "x", retries = 1), SyncInput.serializer())
            }
        assertEquals("x", request.inputData.decode<SyncInput>()?.endpoint)
    }

    @Test
    fun `runImmediately false keeps the default`() {
        val request =
            TaskRequest.periodic(TaskId("sync"), 1.hours) {
                runImmediately(false)
            }
        assertFalse(request.runImmediately)
    }

    @Test
    fun `calendar request stores the expression and never runs immediately`() {
        val cron = CronExpression.everyDayAt(LocalTime.of(7, 30))
        val request =
            TaskRequest.calendar(TaskId("daily"), cron) {
                existingTaskPolicy(ExistingTaskPolicy.UPDATE_DATA)
                retryPolicy(RetryPolicy.ExponentialBackoff())
            }

        assertEquals(TaskRequest.Type.CALENDAR, request.type)
        assertNull(request.interval)
        assertEquals(cron, request.cronExpression)
        assertFalse(request.runImmediately)
        assertEquals(ExistingTaskPolicy.UPDATE_DATA, request.existingTaskPolicy)
        assertTrue(request.retryPolicy is RetryPolicy.ExponentialBackoff)
    }

    @Test
    fun `onBoot request has no interval or cron`() {
        val request = TaskRequest.onBoot(TaskId("startup"))
        assertEquals(TaskRequest.Type.ON_BOOT, request.type)
        assertNull(request.interval)
        assertNull(request.cronExpression)
        assertFalse(request.runImmediately)
        assertEquals(ExistingTaskPolicy.KEEP, request.existingTaskPolicy)
    }

    @Test
    fun `Type contains the three schedule kinds`() {
        assertEquals(
            listOf(TaskRequest.Type.PERIODIC, TaskRequest.Type.CALENDAR, TaskRequest.Type.ON_BOOT),
            TaskRequest.Type.entries,
        )
    }
}
