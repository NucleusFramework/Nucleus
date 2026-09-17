package dev.nucleusframework.scheduler.testing

import dev.nucleusframework.scheduler.CronExpression
import dev.nucleusframework.scheduler.DesktopTask
import dev.nucleusframework.scheduler.DesktopTaskScheduler
import dev.nucleusframework.scheduler.ExistingTaskPolicy
import dev.nucleusframework.scheduler.LastTaskResult
import dev.nucleusframework.scheduler.NetworkType
import dev.nucleusframework.scheduler.TaskContext
import dev.nucleusframework.scheduler.TaskData
import dev.nucleusframework.scheduler.TaskId
import dev.nucleusframework.scheduler.TaskRegistry
import dev.nucleusframework.scheduler.TaskRequest
import dev.nucleusframework.scheduler.TaskResult
import dev.nucleusframework.scheduler.TaskState
import dev.nucleusframework.scheduler.decode
import dev.nucleusframework.scheduler.inputData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// -- Task IDs ----------------------------------------------------------------

private val SuccessId = TaskId("success")
private val FailingId = TaskId("failing")
private val RetryId = TaskId("retry")
private val EchoId = TaskId("echo")
private val CounterId = TaskId("counter")
private val FastId = TaskId("fast")
private val SlowId = TaskId("slow")
private val TaskAId = TaskId("a")
private val TaskBId = TaskId("b")
private val GenericTaskId = TaskId("task")
private val BootId = TaskId("boot")
private val FlakyId = TaskId("flaky")
private val UnknownId = TaskId("unknown")

// -- Serializable payloads for testing ---------------------------------------

@Serializable
private data class KeyValue(
    val key: String,
)

@Serializable
private data class Versioned(
    val version: String,
)

@Serializable
private data class Endpoint(
    val endpoint: String,
    val token: String,
)

// -- Sample tasks for testing -------------------------------------------------

class SuccessTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult = TaskResult.Success
}

class FailingTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult = TaskResult.Failure("something went wrong")
}

class RetryTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult =
        if (context.runAttemptCount < 3) {
            TaskResult.Retry("not ready yet")
        } else {
            TaskResult.Success
        }
}

class InputEchoTask : DesktopTask {
    var receivedData: TaskData = TaskData.EMPTY

    override suspend fun doWork(context: TaskContext): TaskResult {
        receivedData = context.rawInputData
        return TaskResult.Success
    }
}

class CountingTask : DesktopTask {
    var executions = 0

    override suspend fun doWork(context: TaskContext): TaskResult {
        executions++
        return TaskResult.Success
    }
}

// -- Level 1: TestTaskRunner --------------------------------------------------

class TestTaskRunnerTest {
    @Test
    fun `runs a successful task`() =
        runBlocking {
            val result = TestTaskRunner.runTask(task = SuccessTask())
            assertEquals(TaskResult.Success, result)
        }

    @Test
    fun `runs a failing task`() =
        runBlocking {
            val result = TestTaskRunner.runTask(task = FailingTask())
            assertTrue(result is TaskResult.Failure)
            assertEquals("something went wrong", result.message)
        }

    @Test
    fun `passes input data to task context`() =
        runBlocking {
            val task = InputEchoTask()
            TestTaskRunner.runTask(
                task = task,
                inputData = TaskData.of(KeyValue(key = "value")),
            )
            assertEquals("value", task.receivedData.decode<KeyValue>()?.key)
        }

    @Test
    fun `passes run attempt count to task context`() =
        runBlocking {
            val retryResult = TestTaskRunner.runTask(task = RetryTask(), runAttemptCount = 1)
            assertTrue(retryResult is TaskResult.Retry)

            val successResult = TestTaskRunner.runTask(task = RetryTask(), runAttemptCount = 3)
            assertEquals(TaskResult.Success, successResult)
        }
}

// -- Level 2: TestDesktopTaskScheduler ----------------------------------------

class TestDesktopTaskSchedulerTest {
    private val registry =
        TaskRegistry
            .Builder()
            .register(SuccessId) { SuccessTask() }
            .register(FailingId) { FailingTask() }
            .register(RetryId) { RetryTask() }
            .build()

    @Test
    fun `enqueue and isScheduled`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()

            DesktopTaskScheduler.enqueue(TaskRequest.periodic(SuccessId, 1.hours))
            assertTrue(DesktopTaskScheduler.isScheduled(SuccessId))
            assertFalse(DesktopTaskScheduler.isScheduled(UnknownId))
        }
    }

    @Test
    fun `cancel removes task`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()

            DesktopTaskScheduler.enqueue(TaskRequest.periodic(SuccessId, 1.hours))
            assertTrue(DesktopTaskScheduler.cancel(SuccessId))
            assertFalse(DesktopTaskScheduler.isScheduled(SuccessId))
            assertFalse(DesktopTaskScheduler.cancel(SuccessId))
        }
    }

    @Test
    fun `cancelAll clears everything`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()

            DesktopTaskScheduler.enqueue(TaskRequest.periodic(SuccessId, 1.hours))
            DesktopTaskScheduler.enqueue(TaskRequest.onBoot(RetryId))
            assertEquals(2, DesktopTaskScheduler.getAllTasks().size)

            DesktopTaskScheduler.cancelAll()
            assertTrue(DesktopTaskScheduler.getAllTasks().isEmpty())
        }
    }

    @Test
    fun `runTask executes task and updates info`() =
        runBlocking {
            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic(SuccessId, 1.hours))

                val result = scheduler.runTask(SuccessId, registry)
                assertEquals(TaskResult.Success, result)

                val info = DesktopTaskScheduler.getTaskInfo(SuccessId)
                assertNotNull(info)
                assertEquals(1, info.runCount)
                assertEquals(LastTaskResult.Success, info.lastResult)
                assertEquals(TaskState.SCHEDULED, info.state)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `runTask tracks retry attempts automatically`() =
        runBlocking {
            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic(RetryId, 1.hours))

                // First attempt — retry (runAttemptCount = 1)
                val r1 = scheduler.runTask(RetryId, registry)
                assertTrue(r1 is TaskResult.Retry)

                // Second attempt — retry (runAttemptCount auto-incremented to 2)
                val r2 = scheduler.runTask(RetryId, registry)
                assertTrue(r2 is TaskResult.Retry)

                // Third attempt — success (runAttemptCount auto-incremented to 3)
                val r3 = scheduler.runTask(RetryId, registry)
                assertEquals(TaskResult.Success, r3)

                // Verify via execution history
                val history = scheduler.getExecutionHistory(RetryId)
                assertEquals(3, history.size)
                assertEquals(1, history[0].runAttemptCount)
                assertEquals(2, history[1].runAttemptCount)
                assertEquals(3, history[2].runAttemptCount)

                val info = DesktopTaskScheduler.getTaskInfo(RetryId)
                assertNotNull(info)
                assertEquals(1, info.runCount)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `getEnqueuedRequest returns request`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()

            val request =
                TaskRequest.periodic(SuccessId, 1.hours) {
                    inputData(KeyValue(key = "value"))
                }
            DesktopTaskScheduler.enqueue(request)

            val stored = scheduler.getEnqueuedRequest(SuccessId)
            assertNotNull(stored)
            assertEquals("value", stored.inputData.decode<KeyValue>()?.key)
            assertNull(scheduler.getEnqueuedRequest(UnknownId))
        }
    }

    @Test
    fun `KEEP policy does not replace existing task`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()

            DesktopTaskScheduler.enqueue(
                TaskRequest.periodic(SuccessId, 1.hours) {
                    inputData(Versioned(version = "1"))
                },
            )
            DesktopTaskScheduler.enqueue(
                TaskRequest.periodic(SuccessId, 2.hours) {
                    inputData(Versioned(version = "2"))
                },
            )

            val stored = scheduler.getEnqueuedRequest(SuccessId)
            assertNotNull(stored)
            assertEquals("1", stored.inputData.decode<Versioned>()?.version)
        }
    }

    @Test
    fun `REPLACE policy overwrites existing task`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()

            DesktopTaskScheduler.enqueue(
                TaskRequest.periodic(SuccessId, 1.hours) {
                    inputData(Versioned(version = "1"))
                },
            )
            DesktopTaskScheduler.enqueue(
                TaskRequest.periodic(SuccessId, 2.hours) {
                    inputData(Versioned(version = "2"))
                    existingTaskPolicy(ExistingTaskPolicy.REPLACE)
                },
            )

            val stored = scheduler.getEnqueuedRequest(SuccessId)
            assertNotNull(stored)
            assertEquals("2", stored.inputData.decode<Versioned>()?.version)
        }
    }

    @Test
    fun `UPDATE_DATA policy refreshes payload without replacing schedule`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()

            DesktopTaskScheduler.enqueue(
                TaskRequest.periodic(SuccessId, 1.hours) {
                    inputData(Versioned(version = "1"))
                },
            )
            DesktopTaskScheduler.enqueue(
                TaskRequest.periodic(SuccessId, 1.hours) {
                    inputData(Versioned(version = "2"))
                    existingTaskPolicy(ExistingTaskPolicy.UPDATE_DATA)
                },
            )

            val stored = scheduler.getEnqueuedRequest(SuccessId)
            assertNotNull(stored)
            assertEquals("2", stored.inputData.decode<Versioned>()?.version)
        }
    }

    @Test
    fun `input data flows through to runTask`() =
        runBlocking {
            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                val task = InputEchoTask()
                val customRegistry =
                    TaskRegistry
                        .Builder()
                        .register(EchoId) { task }
                        .build()

                DesktopTaskScheduler.enqueue(
                    TaskRequest.periodic(EchoId, 1.hours) {
                        inputData(Endpoint(endpoint = "https://test.api", token = "abc123"))
                    },
                )

                scheduler.runTask(EchoId, customRegistry)
                val decoded = task.receivedData.decode<Endpoint>()
                assertEquals("https://test.api", decoded?.endpoint)
                assertEquals("abc123", decoded?.token)
            } finally {
                scheduler.uninstall()
            }
        }
}

// -- advanceTimeBy tests ------------------------------------------------------

class AdvanceTimeTest {
    @Test
    fun `advanceTimeBy triggers periodic task at correct intervals`() =
        runBlocking {
            val task = CountingTask()
            val registry =
                TaskRegistry
                    .Builder()
                    .register(CounterId) { task }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic(CounterId, 2.hours))

                val results = scheduler.advanceTimeBy(6.hours, registry)
                assertEquals(3, results.size)
                assertEquals(3, task.executions)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `advanceTimeBy does not trigger before first interval`() =
        runBlocking {
            val task = CountingTask()
            val registry =
                TaskRegistry
                    .Builder()
                    .register(CounterId) { task }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic(CounterId, 2.hours))

                val results = scheduler.advanceTimeBy(90.minutes, registry)
                assertEquals(0, results.size)
                assertEquals(0, task.executions)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `advanceTimeBy fires exactly on interval boundary`() =
        runBlocking {
            val task = CountingTask()
            val registry =
                TaskRegistry
                    .Builder()
                    .register(CounterId) { task }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic(CounterId, 1.hours))

                // Exactly 1 hour = exactly 1 fire
                val results = scheduler.advanceTimeBy(1.hours, registry)
                assertEquals(1, results.size)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `advanceTimeBy with multiple tasks`() =
        runBlocking {
            val fastTask = CountingTask()
            val slowTask = CountingTask()
            val registry =
                TaskRegistry
                    .Builder()
                    .register(FastId) { fastTask }
                    .register(SlowId) { slowTask }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic(FastId, 1.hours))
                DesktopTaskScheduler.enqueue(TaskRequest.periodic(SlowId, 3.hours))

                val results = scheduler.advanceTimeBy(6.hours, registry)
                // fast: 1h, 2h, 3h, 4h, 5h, 6h = 6 fires
                // slow: 3h, 6h = 2 fires
                assertEquals(8, results.size)
                assertEquals(6, fastTask.executions)
                assertEquals(2, slowTask.executions)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `advanceTimeBy records virtual time in execution history`() =
        runBlocking {
            val registry =
                TaskRegistry
                    .Builder()
                    .register(GenericTaskId) { SuccessTask() }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic(GenericTaskId, 1.hours))

                scheduler.advanceTimeBy(3.hours, registry)

                val history = scheduler.getExecutionHistory(GenericTaskId)
                assertEquals(3, history.size)
                assertEquals(1.hours.inWholeMilliseconds, history[0].virtualTimeMs)
                assertEquals(2.hours.inWholeMilliseconds, history[1].virtualTimeMs)
                assertEquals(3.hours.inWholeMilliseconds, history[2].virtualTimeMs)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `advanceTimeBy skips non-periodic tasks`() =
        runBlocking {
            val registry =
                TaskRegistry
                    .Builder()
                    .register(BootId) { SuccessTask() }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.onBoot(BootId))

                val results = scheduler.advanceTimeBy(5.hours, registry)
                assertEquals(0, results.size)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `advanceTimeBy with retry tracks attempt count across fires`() =
        runBlocking {
            // Task retries twice then succeeds on 3rd attempt
            val registry =
                TaskRegistry
                    .Builder()
                    .register(FlakyId) { RetryTask() }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic(FlakyId, 1.hours))

                // First fire at 1h — retry (attempt 1)
                scheduler.advanceTimeBy(1.hours, registry)
                var history = scheduler.getExecutionHistory(FlakyId)
                assertEquals(1, history.size)
                assertTrue(history[0].result is LastTaskResult.Retry)
                assertEquals(1, history[0].runAttemptCount)

                // Second fire at 2h — retry (attempt 2, auto-incremented)
                scheduler.advanceTimeBy(1.hours, registry)
                history = scheduler.getExecutionHistory(FlakyId)
                assertEquals(2, history.size)
                assertTrue(history[1].result is LastTaskResult.Retry)
                assertEquals(2, history[1].runAttemptCount)

                // Third fire at 3h — success (attempt 3, auto-incremented)
                scheduler.advanceTimeBy(1.hours, registry)
                history = scheduler.getExecutionHistory(FlakyId)
                assertEquals(3, history.size)
                assertEquals(LastTaskResult.Success, history[2].result)
                assertEquals(3, history[2].runAttemptCount)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `getAllExecutionHistory returns chronologically sorted records`() =
        runBlocking {
            val registry =
                TaskRegistry
                    .Builder()
                    .register(TaskAId) { SuccessTask() }
                    .register(TaskBId) { SuccessTask() }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic(TaskAId, 1.hours))
                DesktopTaskScheduler.enqueue(TaskRequest.periodic(TaskBId, 2.hours))

                scheduler.advanceTimeBy(4.hours, registry)

                val all = scheduler.getAllExecutionHistory()
                // a: 1h, 2h, 3h, 4h = 4 fires; b: 2h, 4h = 2 fires
                assertEquals(6, all.size)
                // Verify chronological order
                for (i in 1 until all.size) {
                    assertTrue(all[i].virtualTimeMs >= all[i - 1].virtualTimeMs)
                }
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `advanceTimeBy skips calendar tasks`() =
        runBlocking {
            val registry =
                TaskRegistry
                    .Builder()
                    .register(GenericTaskId) { SuccessTask() }
                    .build()
            TestDesktopTaskScheduler().use { scheduler ->
                scheduler.install()
                DesktopTaskScheduler.enqueue(
                    TaskRequest.calendar(GenericTaskId, CronExpression.everyDayAt(LocalTime.of(9, 0))),
                )
                val results = scheduler.advanceTimeBy(24.hours, registry)
                assertEquals(0, results.size)
                assertTrue(scheduler.getExecutionHistory(GenericTaskId).isEmpty())
            }
        }
}

class TestDesktopTaskSchedulerMoreTest {
    @Test
    fun `runTask throws when the task is not enqueued`() =
        runBlocking {
            val registry = TaskRegistry.Builder().register(SuccessId) { SuccessTask() }.build()
            TestDesktopTaskScheduler().use { scheduler ->
                val error =
                    kotlin.test.assertFailsWith<IllegalStateException> {
                        scheduler.runTask(SuccessId, registry)
                    }
                assertTrue(error.message!!.contains(SuccessId.value))
            }
        }

    @Test
    fun `runTask records failure and resets the attempt counter`() =
        runBlocking {
            val registry = TaskRegistry.Builder().register(FailingId) { FailingTask() }.build()
            TestDesktopTaskScheduler().use { scheduler ->
                scheduler.install()
                DesktopTaskScheduler.enqueue(TaskRequest.periodic(FailingId, 1.hours))
                val result = scheduler.runTask(FailingId, registry)
                assertTrue(result is TaskResult.Failure)
                assertEquals("something went wrong", result.message)
                val info = DesktopTaskScheduler.getTaskInfo(FailingId)
                assertNotNull(info)
                assertEquals(0, info.runCount)
                assertTrue(info.lastResult is LastTaskResult.Failure)
                val history = scheduler.getExecutionHistory(FailingId)
                assertEquals(1, history.size)
                assertEquals(1, history[0].runAttemptCount)
            }
        }

    @Test
    fun `getTaskInfo is null for a task that was never enqueued`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()
            assertNull(DesktopTaskScheduler.getTaskInfo(UnknownId))
            assertTrue(scheduler.getEnqueuedRequests().isEmpty())
            assertEquals(0L, scheduler.currentVirtualTimeMs)
        }
    }

    @Test
    fun `getEnqueuedRequests returns every stored request`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()
            DesktopTaskScheduler.enqueue(TaskRequest.periodic(SuccessId, 1.hours))
            DesktopTaskScheduler.enqueue(TaskRequest.onBoot(BootId))
            val ids = scheduler.getEnqueuedRequests().map { it.taskId }.toSet()
            assertEquals(setOf(SuccessId, BootId), ids)
        }
    }

    @Test
    fun `UPDATE_DATA keeps the original enqueue time`() =
        runBlocking {
            val registry = TaskRegistry.Builder().register(SuccessId) { SuccessTask() }.build()
            TestDesktopTaskScheduler().use { scheduler ->
                scheduler.install()
                DesktopTaskScheduler.enqueue(
                    TaskRequest.periodic(SuccessId, 2.hours) {
                        inputData(Versioned(version = "1"))
                    },
                )
                scheduler.advanceTimeBy(1.hours, registry)
                DesktopTaskScheduler.enqueue(
                    TaskRequest.periodic(SuccessId, 2.hours) {
                        inputData(Versioned(version = "2"))
                        existingTaskPolicy(ExistingTaskPolicy.UPDATE_DATA)
                    },
                )
                val afterUpdate = scheduler.advanceTimeBy(1.hours, registry)
                assertEquals(1, afterUpdate.size)
                assertEquals(
                    "2",
                    scheduler
                        .getEnqueuedRequest(SuccessId)
                        ?.inputData
                        ?.decode<Versioned>()
                        ?.version,
                )
            }
        }

    @Test
    fun `periodic constraint skip does not increment the attempt counter`() =
        runBlocking {
            val checker = TestConstraintChecker()
            checker.networkConnected = false
            val registry = TaskRegistry.Builder().register(SuccessId) { SuccessTask() }.build()
            TestDesktopTaskScheduler(constraintChecker = checker).use { scheduler ->
                scheduler.install()
                DesktopTaskScheduler.enqueue(
                    TaskRequest.periodic(SuccessId, 1.hours) {
                        constraints { requiredNetworkType = NetworkType.CONNECTED }
                    },
                )
                val records = scheduler.advanceTimeBy(2.hours, registry)
                assertEquals(2, records.size)
                assertTrue(records.all { it.result is LastTaskResult.ConstraintsNotMet })
                assertEquals(listOf(1, 1), records.map { it.runAttemptCount })
            }
        }

    @Test
    fun `calendar constraint skip increments the attempt counter`() =
        runBlocking {
            val checker = TestConstraintChecker()
            checker.availableStorageBytes = 0
            val registry = TaskRegistry.Builder().register(GenericTaskId) { SuccessTask() }.build()
            TestDesktopTaskScheduler(constraintChecker = checker).use { scheduler ->
                scheduler.install()
                DesktopTaskScheduler.enqueue(
                    TaskRequest.calendar(GenericTaskId, CronExpression.everyHour()) {
                        constraints { minimumStorageBytes = 1 }
                    },
                )
                assertNull(scheduler.runTask(GenericTaskId, registry))
                val history = scheduler.getExecutionHistory(GenericTaskId)
                assertEquals(1, history.size)
                assertTrue(history[0].result is LastTaskResult.ConstraintsNotMet)
                assertEquals(1, history[0].runAttemptCount)
                assertNull(scheduler.runTask(GenericTaskId, registry))
                assertEquals(2, scheduler.getExecutionHistory(GenericTaskId)[1].runAttemptCount)
            }
        }

    @Test
    fun `onBoot constraint skip increments the attempt counter`() =
        runBlocking {
            val checker = TestConstraintChecker()
            checker.isCharging = false
            val registry = TaskRegistry.Builder().register(BootId) { SuccessTask() }.build()
            TestDesktopTaskScheduler(constraintChecker = checker).use { scheduler ->
                scheduler.install()
                DesktopTaskScheduler.enqueue(
                    TaskRequest.onBoot(BootId) {
                        constraints { requiresCharging = true }
                    },
                )
                assertNull(scheduler.runTask(BootId, registry))
                val skip = scheduler.getExecutionHistory(BootId).single().result
                assertTrue(skip is LastTaskResult.ConstraintsNotMet)
                assertEquals(setOf("charging"), skip.unsatisfied)
            }
        }

    @Test
    fun `ExecutionRecord equality is structural`() {
        val record =
            TestDesktopTaskScheduler.ExecutionRecord(
                taskId = SuccessId,
                result = LastTaskResult.Success,
                runAttemptCount = 1,
                virtualTimeMs = 1000L,
            )
        assertEquals(
            record,
            TestDesktopTaskScheduler.ExecutionRecord(
                taskId = SuccessId,
                result = LastTaskResult.Success,
                runAttemptCount = 1,
                virtualTimeMs = 1000L,
            ),
        )
        assertEquals(
            record.hashCode(),
            TestDesktopTaskScheduler
                .ExecutionRecord(
                    taskId = SuccessId,
                    result = LastTaskResult.Success,
                    runAttemptCount = 1,
                    virtualTimeMs = 1000L,
                ).hashCode(),
        )
    }

    @Test
    fun `TestTaskRunner default task id is test-task`() =
        runBlocking {
            val task =
                object : DesktopTask {
                    var seen: TaskId? = null

                    override suspend fun doWork(context: TaskContext): TaskResult {
                        seen = context.taskId
                        return TaskResult.Success
                    }
                }
            val result = TestTaskRunner.runTask(task)
            assertEquals(TaskResult.Success, result)
            assertEquals(TaskId("test-task"), task.seen)
        }
}
