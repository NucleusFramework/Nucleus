package dev.nucleusframework.nucleus.scheduler.testing

import dev.nucleusframework.nucleus.scheduler.DesktopTask
import dev.nucleusframework.nucleus.scheduler.TaskContext
import dev.nucleusframework.nucleus.scheduler.TaskData
import dev.nucleusframework.nucleus.scheduler.TaskId
import dev.nucleusframework.nucleus.scheduler.TaskResult

/**
 * Runs a [DesktopTask] in isolation without any OS scheduler involvement.
 *
 * Use this to unit-test your task's `doWork()` logic with a controlled [TaskContext]:
 *
 * ```kotlin
 * val result = TestTaskRunner.runTask(
 *     task = SyncTask(),
 *     taskId = TaskId("sync"),
 *     inputData = TaskData.of(SyncInput(endpoint = "https://test.api")),
 * )
 * assertEquals(TaskResult.Success, result)
 * ```
 */
public object TestTaskRunner {
    /**
     * Executes [task]`.doWork()` with a fabricated [TaskContext] and returns the result.
     *
     * @param task the task to execute
     * @param taskId the task identifier passed in the context (default: `TaskId("test-task")`)
     * @param inputData serialized payload exposed as [TaskContext.rawInputData] and decoded via the
     *   `context.inputData<T>()` extension (default: [TaskData.EMPTY])
     * @param runAttemptCount 1-based attempt counter (default: `1`)
     */
    public suspend fun runTask(
        task: DesktopTask,
        taskId: TaskId = TaskId("test-task"),
        inputData: TaskData = TaskData.EMPTY,
        runAttemptCount: Int = 1,
    ): TaskResult {
        val context =
            TaskContext(
                taskId = taskId,
                rawInputData = inputData,
                runAttemptCount = runAttemptCount,
            )
        return task.doWork(context)
    }
}
