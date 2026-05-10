package dev.nucleusframework.nucleus.scheduler

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Execution context passed to [DesktopTask.doWork].
 *
 * @property taskId the unique identifier of this task
 * @property rawInputData the serialized input payload wrapper — prefer the typed
 *   [inputData] extensions; access this directly only when you need the raw [TaskData].
 * @property runAttemptCount 1-based attempt counter (1 = first run, 2 = first retry, etc.)
 */
public data class TaskContext(
    val taskId: TaskId,
    val rawInputData: TaskData = TaskData.EMPTY,
    val runAttemptCount: Int = 1,
)

/**
 * Decodes the [TaskContext.rawInputData] payload as [T] using [serializer].
 *
 * Returns `null` when no payload was attached at enqueue time.
 */
public fun <T> TaskContext.inputData(serializer: KSerializer<T>): T? = rawInputData.decode(serializer)

/**
 * Decodes the [TaskContext.rawInputData] payload as [T] using the contextually-resolved serializer.
 *
 * Returns `null` when no payload was attached at enqueue time.
 */
public inline fun <reified T> TaskContext.inputData(): T? = rawInputData.decode(serializer<T>())
