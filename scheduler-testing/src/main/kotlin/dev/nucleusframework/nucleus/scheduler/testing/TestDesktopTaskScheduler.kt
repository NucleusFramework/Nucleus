package dev.nucleusframework.nucleus.scheduler.testing

import dev.nucleusframework.nucleus.scheduler.Constraints
import dev.nucleusframework.nucleus.scheduler.DesktopTaskScheduler
import dev.nucleusframework.nucleus.scheduler.ExistingTaskPolicy
import dev.nucleusframework.nucleus.scheduler.InternalSchedulerApi
import dev.nucleusframework.nucleus.scheduler.LastTaskResult
import dev.nucleusframework.nucleus.scheduler.TaskContext
import dev.nucleusframework.nucleus.scheduler.TaskId
import dev.nucleusframework.nucleus.scheduler.TaskInfo
import dev.nucleusframework.nucleus.scheduler.TaskRegistry
import dev.nucleusframework.nucleus.scheduler.TaskRequest
import dev.nucleusframework.nucleus.scheduler.TaskResult
import dev.nucleusframework.nucleus.scheduler.TaskState
import dev.nucleusframework.nucleus.scheduler.internal.ConstraintChecker
import dev.nucleusframework.nucleus.scheduler.internal.ConstraintResult
import dev.nucleusframework.nucleus.scheduler.internal.PlatformScheduler
import java.io.Closeable
import kotlin.time.Duration

/**
 * In-memory scheduler for integration tests.
 *
 * Replaces the real [DesktopTaskScheduler] backend so you can enqueue, query,
 * and execute tasks without touching the OS scheduler. Supports virtual time
 * advancement to test periodic and calendar schedules.
 *
 * ```kotlin
 * TestDesktopTaskScheduler().use { testScheduler ->
 *     testScheduler.install()
 *
 *     DesktopTaskScheduler.enqueue(TaskRequest.periodic("sync", 2.hours))
 *
 *     // Advance 6 hours — triggers 3 executions
 *     val results = testScheduler.advanceTimeBy(6.hours, registry)
 *     assertEquals(3, results.size)
 *
 *     // Inspect execution history
 *     val history = testScheduler.getExecutionHistory("sync")
 *     assertEquals(3, history.size)
 * }
 * ```
 */
@InternalSchedulerApi
internal object AllSatisfiedConstraintChecker : ConstraintChecker {
    override fun check(constraints: Constraints): ConstraintResult =
        ConstraintResult(satisfied = true, unsatisfied = emptySet())
}

@OptIn(InternalSchedulerApi::class)
public class TestDesktopTaskScheduler(
    /**
     * Optional constraint checker used to simulate constraint failures.
     *
     * When non-null, its lifecycle (install / uninstall on `DesktopBootReceiver`) is
     * co-managed with this scheduler — calling [install] / [close] also installs and
     * uninstalls the checker. When null, all constraints are treated as satisfied.
     */
    public val constraintChecker: TestConstraintChecker? = null,
) : PlatformScheduler,
    Closeable {
    private val internalChecker: ConstraintChecker =
        constraintChecker ?: AllSatisfiedConstraintChecker
    private val tasks = mutableMapOf<TaskId, TaskRequest>()
    private val metadata = mutableMapOf<TaskId, TaskMetadata>()
    private val executionHistories = mutableMapOf<TaskId, MutableList<ExecutionRecord>>()
    private var virtualTimeMs: Long = 0L
    private val enqueueTimeMs = mutableMapOf<TaskId, Long>()

    private data class TaskMetadata(
        var runCount: Int = 0,
        var runAttemptCount: Int = 1,
        var lastRunMs: Long? = null,
        var lastResult: LastTaskResult? = null,
    )

    /**
     * A recorded task execution or skip.
     *
     * @property taskId the task that fired
     * @property result the outcome — `Success`, `Failure`, `Retry` (if `doWork()` ran) or
     *   `ConstraintsNotMet` (if execution was skipped before `doWork()`)
     * @property runAttemptCount the attempt number at the time of execution (1-based)
     * @property virtualTimeMs the virtual time at which the firing occurred
     */
    public data class ExecutionRecord(
        public val taskId: TaskId,
        public val result: LastTaskResult,
        public val runAttemptCount: Int,
        public val virtualTimeMs: Long,
    )

    // -- Install / Uninstall --------------------------------------------------

    /**
     * Installs this test scheduler as the active backend for [DesktopTaskScheduler].
     *
     * After calling this, all calls to `DesktopTaskScheduler.enqueue()`, `.cancel()`, etc.
     * are routed to this in-memory implementation. If [constraintChecker] is a
     * [TestConstraintChecker], it is installed on `DesktopBootReceiver` as well so the
     * production constraint-checking code path also routes through it.
     */
    public fun install() {
        DesktopTaskScheduler.setTestDelegate(this)
        constraintChecker?.install()
    }

    /**
     * Restores the platform-default scheduler backend (and constraint checker, if a
     * [TestConstraintChecker] was installed by [install]).
     */
    public fun uninstall() {
        constraintChecker?.uninstall()
        DesktopTaskScheduler.resetDelegate()
    }

    override fun close() {
        uninstall()
    }

    // -- PlatformScheduler implementation -------------------------------------

    override fun enqueue(request: TaskRequest): Boolean {
        val existing = tasks[request.taskId]
        if (existing != null) {
            when (request.existingTaskPolicy) {
                ExistingTaskPolicy.KEEP -> return true
                ExistingTaskPolicy.UPDATE_DATA -> {
                    // Replace the registered TaskRequest so getEnqueuedRequest reflects the new data,
                    // but preserve the original enqueue time so periodic firing stays aligned with
                    // the existing schedule.
                    tasks[request.taskId] = request
                    return true
                }
                ExistingTaskPolicy.REPLACE -> Unit // fall through to full replace
            }
        }
        tasks[request.taskId] = request
        metadata.getOrPut(request.taskId) { TaskMetadata() }
        enqueueTimeMs[request.taskId] = virtualTimeMs
        return true
    }

    override fun cancel(taskId: TaskId): Boolean {
        val removed = tasks.remove(taskId) != null
        if (removed) {
            metadata.remove(taskId)
            enqueueTimeMs.remove(taskId)
        }
        return removed
    }

    override fun cancelAll() {
        tasks.clear()
        metadata.clear()
        enqueueTimeMs.clear()
    }

    override fun isScheduled(taskId: TaskId): Boolean = taskId in tasks

    override fun getTaskInfo(taskId: TaskId): TaskInfo? {
        if (taskId !in tasks) return null
        val meta = metadata[taskId] ?: return null
        return TaskInfo(
            taskId = taskId,
            state = TaskState.SCHEDULED,
            lastRunMs = meta.lastRunMs,
            nextRunMs = null,
            runCount = meta.runCount,
            lastResult = meta.lastResult,
        )
    }

    override fun getAllTasks(): List<TaskInfo> = tasks.keys.mapNotNull { getTaskInfo(it) }

    // -- Test helpers ---------------------------------------------------------

    /**
     * Immediately executes the task identified by [taskId] using the given [registry].
     *
     * If the task has [Constraints] and the [constraintChecker] reports them as unsatisfied,
     * `doWork()` is **not** invoked: an [ExecutionRecord] with
     * [LastTaskResult.ConstraintsNotMet] is appended to the history and this method returns
     * `null`. Calendar / on-boot tasks additionally bump the attempt counter (matching the
     * production retry semantics in `DesktopBootReceiver`).
     *
     * Otherwise the return value is the [TaskResult] produced by `doWork()`.
     *
     * @throws IllegalStateException if the task is not enqueued
     * @throws dev.nucleusframework.nucleus.scheduler.TaskNotFoundException if [taskId] is not in [registry]
     */
    public suspend fun runTask(
        taskId: TaskId,
        registry: TaskRegistry,
    ): TaskResult? {
        val request =
            tasks[taskId]
                ?: error("Task '$taskId' is not enqueued in the test scheduler")
        val meta = metadata.getOrPut(taskId) { TaskMetadata() }

        // Check constraints before executing
        if (request.constraints.hasConstraints()) {
            val checkResult = internalChecker.check(request.constraints)
            if (!checkResult.satisfied) {
                val skip = LastTaskResult.ConstraintsNotMet(checkResult.unsatisfied)
                executionHistories.getOrPut(taskId) { mutableListOf() }.add(
                    ExecutionRecord(
                        taskId = taskId,
                        result = skip,
                        runAttemptCount = meta.runAttemptCount,
                        virtualTimeMs = virtualTimeMs,
                    ),
                )
                if (request.type != TaskRequest.Type.PERIODIC) {
                    meta.runAttemptCount++
                }
                meta.lastRunMs = virtualTimeMs
                meta.lastResult = skip
                return null
            }
        }

        val context =
            TaskContext(
                taskId = taskId,
                rawInputData = request.inputData,
                runAttemptCount = meta.runAttemptCount,
            )

        val task = registry.create(taskId)
        val result = task.doWork(context)

        val typedResult: LastTaskResult =
            when (result) {
                is TaskResult.Success -> LastTaskResult.Success
                is TaskResult.Retry -> LastTaskResult.Retry(result.message)
                is TaskResult.Failure -> LastTaskResult.Failure(result.message)
            }

        executionHistories.getOrPut(taskId) { mutableListOf() }.add(
            ExecutionRecord(
                taskId = taskId,
                result = typedResult,
                runAttemptCount = meta.runAttemptCount,
                virtualTimeMs = virtualTimeMs,
            ),
        )

        when (result) {
            is TaskResult.Success -> {
                meta.runCount++
                meta.runAttemptCount = 1
            }
            is TaskResult.Retry -> meta.runAttemptCount++
            is TaskResult.Failure -> meta.runAttemptCount = 1
        }
        meta.lastRunMs = virtualTimeMs
        meta.lastResult = typedResult

        return result
    }

    // -- Virtual time ---------------------------------------------------------

    /**
     * Returns the current virtual time.
     */
    public val currentVirtualTimeMs: Long get() = virtualTimeMs

    /**
     * Advances virtual time by [duration] and executes all periodic tasks whose
     * interval has elapsed during that window.
     *
     * For a task with interval `I` enqueued at virtual time `T`, executions
     * occur at `T + I`, `T + 2I`, etc. Only periodic tasks are triggered;
     * calendar and on-boot tasks are skipped (use [runTask] for those).
     *
     * Returns all [ExecutionRecord]s produced during the time advancement, in chronological
     * order — including records for fires that were skipped because their constraints were
     * not satisfied (their `result` is [LastTaskResult.ConstraintsNotMet]).
     *
     * ```kotlin
     * DesktopTaskScheduler.enqueue(TaskRequest.periodic("sync", 2.hours))
     * val results = testScheduler.advanceTimeBy(6.hours, registry)
     * assertEquals(3, results.size)
     * ```
     */
    public suspend fun advanceTimeBy(
        duration: Duration,
        registry: TaskRegistry,
    ): List<ExecutionRecord> {
        val targetMs = virtualTimeMs + duration.inWholeMilliseconds
        val records = mutableListOf<ExecutionRecord>()

        // Collect all periodic tasks and compute their fire times
        data class PendingFire(
            val taskId: TaskId,
            val fireAtMs: Long,
        )

        val fires = mutableListOf<PendingFire>()

        for ((taskId, request) in tasks) {
            val interval = request.interval
            if (request.type != TaskRequest.Type.PERIODIC || interval == null) continue
            val intervalMs = interval.inWholeMilliseconds
            val baseMs = enqueueTimeMs[taskId] ?: 0L

            // Compute the next fire time after current virtualTimeMs
            val elapsedSinceEnqueue = virtualTimeMs - baseMs
            val nextIndex = (elapsedSinceEnqueue / intervalMs) + 1
            var nextFireMs = baseMs + nextIndex * intervalMs

            while (nextFireMs <= targetMs) {
                fires.add(PendingFire(taskId, nextFireMs))
                nextFireMs += intervalMs
            }
        }

        // Sort chronologically and execute
        fires.sortBy { it.fireAtMs }

        for (fire in fires) {
            // Task may have been cancelled by a previous execution
            if (fire.taskId in tasks) {
                virtualTimeMs = fire.fireAtMs
                val sizeBefore = executionHistories[fire.taskId]?.size ?: 0
                runTask(fire.taskId, registry)
                val history = executionHistories[fire.taskId]
                if (history != null && history.size > sizeBefore) {
                    records.add(history.last())
                }
            }
        }

        virtualTimeMs = targetMs
        return records
    }

    // -- Execution history ----------------------------------------------------

    /**
     * Returns the full execution history for [taskId], in chronological order.
     *
     * Each entry records the [TaskResult], `runAttemptCount`, and virtual time
     * at which the execution occurred.
     *
     * ```kotlin
     * val history = testScheduler.getExecutionHistory("sync")
     * assertEquals(TaskResult.Success, history.last().result)
     * assertEquals(1, history.last().runAttemptCount)
     * ```
     */
    public fun getExecutionHistory(taskId: TaskId): List<ExecutionRecord> =
        executionHistories[taskId]?.toList() ?: emptyList()

    /**
     * Returns the full execution history across all tasks, in chronological order.
     */
    public fun getAllExecutionHistory(): List<ExecutionRecord> =
        executionHistories.values.flatten().sortedBy { it.virtualTimeMs }

    // -- Enqueue introspection ------------------------------------------------

    /**
     * Returns the [TaskRequest] for an enqueued task, or `null` if not found.
     */
    public fun getEnqueuedRequest(taskId: TaskId): TaskRequest? = tasks[taskId]

    /**
     * Returns all currently enqueued [TaskRequest]s.
     */
    public fun getEnqueuedRequests(): List<TaskRequest> = tasks.values.toList()
}
