package dev.nucleusframework.nucleus.scheduler.internal

import dev.nucleusframework.nucleus.scheduler.InternalSchedulerApi
import dev.nucleusframework.nucleus.scheduler.TaskId
import dev.nucleusframework.nucleus.scheduler.TaskInfo
import dev.nucleusframework.nucleus.scheduler.TaskRequest

/**
 * No-op scheduler for unsupported platforms.
 * All operations silently return failure/empty values.
 */
@OptIn(InternalSchedulerApi::class)
internal object NoopScheduler : PlatformScheduler {
    override fun enqueue(request: TaskRequest): Boolean = false

    override fun cancel(taskId: TaskId): Boolean = false

    override fun cancelAll() = Unit

    override fun isScheduled(taskId: TaskId): Boolean = false

    override fun getTaskInfo(taskId: TaskId): TaskInfo? = null

    override fun getAllTasks(): List<TaskInfo> = emptyList()
}
