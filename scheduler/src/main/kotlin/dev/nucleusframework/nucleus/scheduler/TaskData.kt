package dev.nucleusframework.nucleus.scheduler

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Opaque container for a `@Serializable` payload attached to a [TaskRequest].
 *
 * The payload is encoded as JSON when persisted by the scheduler so it can be
 * re-read later by a fresh process when the OS triggers the task. Decode it
 * inside [DesktopTask.doWork] with [TaskContext.inputData].
 *
 * ```kotlin
 * @Serializable
 * data class SyncInput(val endpoint: String, val retries: Int)
 *
 * TaskRequest.periodic(SyncId, 1.hours) {
 *     inputData(SyncInput(endpoint = "https://api.example.com", retries = 3))
 * }
 *
 * // In SyncTask.doWork:
 * val input = context.inputData<SyncInput>() ?: return TaskResult.Failure("no input")
 * ```
 */
public class TaskData
    @PublishedApi
    internal constructor(
        @PublishedApi internal val json: String?,
    ) {
        /** Returns `true` when no payload was attached. */
        public fun isEmpty(): Boolean = json == null

        /** Returns `true` when a payload was attached. */
        public fun isNotEmpty(): Boolean = json != null

        /**
         * Decodes the payload as [T] using the supplied [serializer].
         *
         * Returns `null` when no payload was attached.
         */
        public fun <T> decode(serializer: KSerializer<T>): T? = json?.let { JSON.decodeFromString(serializer, it) }

        override fun equals(other: Any?): Boolean = other is TaskData && json == other.json

        override fun hashCode(): Int = json?.hashCode() ?: 0

        override fun toString(): String = "TaskData(${json ?: "<empty>"})"

        public companion object {
            /** A [TaskData] with no payload. */
            public val EMPTY: TaskData = TaskData(null)

            @PublishedApi
            internal val JSON: Json = Json { ignoreUnknownKeys = true }

            /** Encodes [value] using the supplied [serializer]. */
            public fun <T> of(
                value: T,
                serializer: KSerializer<T>,
            ): TaskData = TaskData(JSON.encodeToString(serializer, value))

            /** Encodes [value] using the contextually-resolved serializer for [T]. */
            public inline fun <reified T> of(value: T): TaskData = TaskData(JSON.encodeToString(serializer<T>(), value))
        }
    }

/**
 * Decodes the [TaskData] payload as [T] using the contextually-resolved serializer.
 */
public inline fun <reified T> TaskData.decode(): T? = decode(serializer<T>())
