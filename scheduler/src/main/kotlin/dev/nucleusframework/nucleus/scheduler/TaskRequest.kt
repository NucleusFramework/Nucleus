package dev.nucleusframework.nucleus.scheduler

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Describes a task to be scheduled with the OS.
 *
 * Create instances via the companion factory methods: [periodic], [calendar], [onBoot].
 */
@OptIn(InternalSchedulerApi::class)
public class TaskRequest private constructor(
    /** Unique identifier — must match a [TaskRegistry] entry. */
    public val taskId: TaskId,
    @property:InternalSchedulerApi public val type: Type,
    @property:InternalSchedulerApi public val interval: Duration?,
    @property:InternalSchedulerApi public val cronExpression: CronExpression?,
    /** Typed key-value data attached at enqueue time, retrievable via [TaskContext.inputData]. */
    public val inputData: TaskData,
    internal val retryPolicy: RetryPolicy?,
    /** Policy for handling a task with the same ID already scheduled. */
    public val existingTaskPolicy: ExistingTaskPolicy,
    internal val runImmediately: Boolean,
    /** Conditions that must be met before the task executes. */
    public val constraints: Constraints = Constraints.NONE,
) {
    @InternalSchedulerApi
    public enum class Type { PERIODIC, CALENDAR, ON_BOOT }

    /**
     * DSL builder for configuring a [TaskRequest].
     */
    public class Builder internal constructor() {
        @PublishedApi internal var taskData: TaskData = TaskData.EMPTY
        internal var retryPolicy: RetryPolicy? = null
        internal var existingTaskPolicy: ExistingTaskPolicy = ExistingTaskPolicy.KEEP
        internal var runImmediately: Boolean = false
        internal var constraints: Constraints = Constraints.NONE

        /**
         * Attach a `@Serializable` [value] as the task's input payload, decoded via
         * [TaskContext.inputData] inside [DesktopTask.doWork].
         */
        public fun <T> inputData(
            value: T,
            serializer: KSerializer<T>,
        ) {
            taskData = TaskData.of(value, serializer)
        }

        /**
         * Attach a `@Serializable` [value] as the task's input payload, using the
         * contextually-resolved serializer for [T].
         */
        public inline fun <reified T> inputData(value: T) {
            taskData = TaskData.of(value, serializer<T>())
        }

        /** Set the retry policy for this task. */
        public fun retryPolicy(policy: RetryPolicy) {
            retryPolicy = policy
        }

        /** Control behavior when a task with the same ID is already scheduled. */
        public fun existingTaskPolicy(policy: ExistingTaskPolicy) {
            existingTaskPolicy = policy
        }

        /**
         * Run the task immediately when scheduled, in addition to the periodic interval.
         * By default, the first execution waits for the full interval to elapse.
         */
        public fun runImmediately(value: Boolean = true) {
            runImmediately = value
        }

        /** Set pre-built [Constraints] for this task. */
        public fun constraints(constraints: Constraints) {
            this.constraints = constraints
        }

        /** Configure [Constraints] via a DSL block. */
        public fun constraints(configure: ConstraintsBuilder.() -> Unit) {
            this.constraints = ConstraintsBuilder().apply(configure).build()
        }
    }

    public companion object {
        private val MIN_INTERVAL = 15.minutes

        /**
         * A task that repeats at a fixed interval.
         *
         * @param taskId unique identifier matching a [TaskRegistry] entry
         * @param interval minimum 15 minutes
         * @param configure optional DSL block for inputData, retryPolicy, etc.
         */
        public fun periodic(
            taskId: TaskId,
            interval: Duration,
            configure: Builder.() -> Unit = {},
        ): TaskRequest {
            require(interval >= MIN_INTERVAL) {
                "Interval must be at least $MIN_INTERVAL, got $interval"
            }
            val builder = Builder().apply(configure)
            return TaskRequest(
                taskId = taskId,
                type = Type.PERIODIC,
                interval = interval,
                cronExpression = null,
                inputData = builder.taskData,
                retryPolicy = builder.retryPolicy,
                existingTaskPolicy = builder.existingTaskPolicy,
                runImmediately = builder.runImmediately,
                constraints = builder.constraints,
            )
        }

        /**
         * A task triggered on a calendar schedule (cron-like).
         *
         * @param taskId unique identifier matching a [TaskRegistry] entry
         * @param expression the schedule (use [CronExpression] factory methods)
         * @param configure optional DSL block
         */
        public fun calendar(
            taskId: TaskId,
            expression: CronExpression,
            configure: Builder.() -> Unit = {},
        ): TaskRequest {
            val builder = Builder().apply(configure)
            return TaskRequest(
                taskId = taskId,
                type = Type.CALENDAR,
                interval = null,
                cronExpression = expression,
                inputData = builder.taskData,
                retryPolicy = builder.retryPolicy,
                existingTaskPolicy = builder.existingTaskPolicy,
                runImmediately = false,
                constraints = builder.constraints,
            )
        }

        /**
         * A task that runs at system/user login.
         *
         * @param taskId unique identifier matching a [TaskRegistry] entry
         * @param configure optional DSL block
         */
        public fun onBoot(
            taskId: TaskId,
            configure: Builder.() -> Unit = {},
        ): TaskRequest {
            val builder = Builder().apply(configure)
            return TaskRequest(
                taskId = taskId,
                type = Type.ON_BOOT,
                interval = null,
                cronExpression = null,
                inputData = builder.taskData,
                retryPolicy = builder.retryPolicy,
                existingTaskPolicy = builder.existingTaskPolicy,
                runImmediately = false,
                constraints = builder.constraints,
            )
        }
    }
}
