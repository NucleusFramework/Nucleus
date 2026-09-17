package dev.nucleusframework.scheduler

import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.scheduler.internal.ConstraintChecker
import dev.nucleusframework.scheduler.internal.ConstraintResult
import dev.nucleusframework.scheduler.internal.PlatformScheduler
import dev.nucleusframework.scheduler.internal.TaskMetadataStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalSchedulerApi::class)
class DesktopBootReceiverTest {
    private val appId = NucleusApp.appId
    private val successId = TaskId("kover-boot-success")
    private val failId = TaskId("kover-boot-fail")
    private val boomId = TaskId("kover-boot-boom")
    private val skipId = TaskId("kover-boot-skip")
    private val missingId = TaskId("kover-boot-missing")

    @AfterTest
    fun tearDown() {
        DesktopTaskScheduler.resetDelegate()
        DesktopBootReceiver.resetConstraintChecker()
        for (id in listOf(successId, failId, boomId, skipId, missingId)) {
            TaskMetadataStore.delete(appId, id)
        }
    }

    @Test
    fun `isSchedulerInvocation looks for the trigger flag`() {
        assertFalse(DesktopBootReceiver.isSchedulerInvocation(emptyArray()))
        assertFalse(DesktopBootReceiver.isSchedulerInvocation(arrayOf("--other", "sync")))
        assertTrue(DesktopBootReceiver.isSchedulerInvocation(arrayOf(DesktopBootReceiver.SCHEDULER_ARG)))
        assertTrue(DesktopBootReceiver.isSchedulerInvocation(arrayOf("app", DesktopBootReceiver.SCHEDULER_ARG, "sync")))
    }

    @Test
    fun `handle without a task id is a no-op`() {
        val registry = TaskRegistry.Builder().build()
        DesktopBootReceiver.handle(arrayOf(DesktopBootReceiver.SCHEDULER_ARG), registry)
        assertNull(TaskMetadataStore.getLastResult(appId, successId))
    }

    @Test
    fun `handle with an invalid task id is a no-op`() {
        val registry = TaskRegistry.Builder().build()
        DesktopBootReceiver.handle(arrayOf(DesktopBootReceiver.SCHEDULER_ARG, "not valid"), registry)
        assertTrue(TaskMetadataStore.listTaskIds(appId).none { it.value == "not valid" })
    }

    @Test
    fun `handle cancels an unregistered task`() {
        val fake = RecordingScheduler()
        DesktopTaskScheduler.setTestDelegate(fake)
        val registry = TaskRegistry.Builder().build()

        DesktopBootReceiver.handle(arrayOf(DesktopBootReceiver.SCHEDULER_ARG, missingId.value), registry)

        assertEquals(listOf(missingId), fake.cancelled)
    }

    @Test
    fun `handle records success`() {
        val registry =
            TaskRegistry
                .Builder()
                .register(successId) {
                    object : DesktopTask {
                        override suspend fun doWork(context: TaskContext): TaskResult = TaskResult.Success
                    }
                }.build()

        DesktopBootReceiver.handle(arrayOf(DesktopBootReceiver.SCHEDULER_ARG, successId.value), registry)

        assertEquals(LastTaskResult.Success, TaskMetadataStore.getLastResult(appId, successId))
        assertEquals(1, TaskMetadataStore.getRunCount(appId, successId))
        assertEquals(1, TaskMetadataStore.getRunAttemptCount(appId, successId))
    }

    @Test
    fun `handle records failure`() {
        val registry =
            TaskRegistry
                .Builder()
                .register(failId) {
                    object : DesktopTask {
                        override suspend fun doWork(context: TaskContext): TaskResult = TaskResult.Failure("disk full")
                    }
                }.build()

        DesktopBootReceiver.handle(arrayOf(DesktopBootReceiver.SCHEDULER_ARG, failId.value), registry)

        val result = TaskMetadataStore.getLastResult(appId, failId)
        assertTrue(result is LastTaskResult.Failure)
        assertEquals("disk full", result.message)
        assertEquals(1, TaskMetadataStore.getRunAttemptCount(appId, failId))
    }

    @Test
    fun `handle turns an exception into a failure`() {
        val registry =
            TaskRegistry
                .Builder()
                .register(boomId) {
                    object : DesktopTask {
                        override suspend fun doWork(context: TaskContext): TaskResult = error("exploded")
                    }
                }.build()

        DesktopBootReceiver.handle(arrayOf(DesktopBootReceiver.SCHEDULER_ARG, boomId.value), registry)

        val result = TaskMetadataStore.getLastResult(appId, boomId)
        assertTrue(result is LastTaskResult.Failure)
        assertTrue(result.message.contains("exploded"))
    }

    @Test
    fun `handle skips a periodic task when constraints fail`() {
        TaskMetadataStore.saveTaskType(appId, skipId, "PERIODIC")
        TaskMetadataStore.saveConstraints(
            appId,
            skipId,
            Constraints(requiredNetworkType = NetworkType.UNMETERED),
        )
        DesktopBootReceiver.setTestConstraintChecker(
            object : ConstraintChecker {
                override fun check(constraints: Constraints): ConstraintResult =
                    ConstraintResult(satisfied = false, unsatisfied = setOf("network"))
            },
        )
        var ran = false
        val registry =
            TaskRegistry
                .Builder()
                .register(skipId) {
                    object : DesktopTask {
                        override suspend fun doWork(context: TaskContext): TaskResult {
                            ran = true
                            return TaskResult.Success
                        }
                    }
                }.build()

        DesktopBootReceiver.handle(arrayOf(DesktopBootReceiver.SCHEDULER_ARG, skipId.value), registry)

        assertFalse(ran)
        val result = TaskMetadataStore.getLastResult(appId, skipId)
        assertTrue(result is LastTaskResult.ConstraintsNotMet)
        assertEquals(setOf("network"), result.unsatisfied)
        assertEquals(1, TaskMetadataStore.getRunAttemptCount(appId, skipId))
    }

    @Test
    fun `handle loads persisted input data into the context`() {
        val echoId = TaskId("kover-boot-echo")
        try {
            TaskMetadataStore.save(appId, echoId, TaskData.of("payload"))
            var seen: String? = "unset"
            val registry =
                TaskRegistry
                    .Builder()
                    .register(echoId) {
                        object : DesktopTask {
                            override suspend fun doWork(context: TaskContext): TaskResult {
                                seen = context.inputData<String>()
                                return TaskResult.Success
                            }
                        }
                    }.build()

            DesktopBootReceiver.handle(arrayOf(DesktopBootReceiver.SCHEDULER_ARG, echoId.value), registry)

            assertEquals("payload", seen)
        } finally {
            TaskMetadataStore.delete(appId, echoId)
        }
    }

    private class RecordingScheduler : PlatformScheduler {
        val cancelled = mutableListOf<TaskId>()

        override fun enqueue(request: TaskRequest): Boolean = true

        override fun cancel(taskId: TaskId): Boolean {
            cancelled += taskId
            return true
        }

        override fun cancelAll() = Unit

        override fun isScheduled(taskId: TaskId): Boolean = false

        override fun getTaskInfo(taskId: TaskId): TaskInfo? = null

        override fun getAllTasks(): List<TaskInfo> = emptyList()
    }
}
