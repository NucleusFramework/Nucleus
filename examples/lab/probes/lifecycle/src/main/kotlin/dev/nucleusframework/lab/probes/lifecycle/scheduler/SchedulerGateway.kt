package dev.nucleusframework.lab.probes.lifecycle.scheduler

import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.lab.core.process.JvmCommand
import dev.nucleusframework.scheduler.CronExpression
import dev.nucleusframework.scheduler.DesktopTaskScheduler
import dev.nucleusframework.scheduler.ExistingTaskPolicy
import dev.nucleusframework.scheduler.NetworkType
import dev.nucleusframework.scheduler.SchedulerConfig
import dev.nucleusframework.scheduler.TaskData
import dev.nucleusframework.scheduler.TaskId
import dev.nucleusframework.scheduler.TaskInfo
import dev.nucleusframework.scheduler.TaskRequest
import dev.nucleusframework.scheduler.TaskResult
import dev.nucleusframework.scheduler.testing.TestTaskRunner
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.time.LocalTime
import kotlin.time.Duration.Companion.minutes

enum class Trigger(
    val label: String,
) {
    Periodic("Every 15 min, run now"),
    Calendar("Daily, 2 min from now"),
    OnBoot("At login / boot"),
}

data class EnqueueRequest(
    val task: LabTask,
    val trigger: Trigger,
    val note: String,
    val requiresCharging: Boolean,
    val requiresNetwork: Boolean,
)

/** Port over `scheduler`; points the OS scheduler back at this Lab, as a fixture. */
interface SchedulerGateway {
    val isAvailable: Boolean

    /** What the OS will run for [taskId]. */
    fun commandLine(taskId: TaskId): List<String>

    fun enqueue(request: EnqueueRequest): Boolean

    fun cancel(taskId: TaskId): Boolean

    fun cancelAll()

    fun tasks(): List<TaskInfo>

    fun runLog(): List<String>

    /** Runs the task in this process with `scheduler-testing`, no OS involved. */
    suspend fun dryRun(
        task: LabTask,
        note: String,
    ): TaskResult
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NucleusSchedulerGateway : SchedulerGateway {
    init {
        // The OS starts `<executable> <arguments> --nucleus-scheduler-run <id>`. A dev run is a
        // bare `java`, so the arguments must rebuild the JVM (the fixture chosen by system
        // property); a packaged launcher only needs the fixture selector, as a program argument.
        if (!ExecutableRuntime.isDev()) {
            SchedulerConfig.executableArguments = JvmCommand.fixtureArguments(ScheduledTaskFixture.ID)
        } else if (JvmCommand.availability().isAvailable) {
            val command = JvmCommand.fixture(ScheduledTaskFixture.ID)
            SchedulerConfig.executablePath = command.first()
            SchedulerConfig.executableArguments = command.drop(1)
        }
    }

    override val isAvailable: Boolean get() = DesktopTaskScheduler.isAvailable()

    override fun commandLine(taskId: TaskId): List<String> {
        val executable = SchedulerConfig.executablePath ?: JvmCommand.launcher ?: "<launcher>"
        return listOf(executable) + SchedulerConfig.executableArguments +
            listOf(ScheduledTaskFixture.SCHEDULER_ARG, taskId.value)
    }

    override fun enqueue(request: EnqueueRequest): Boolean {
        val input = LabTaskInput(request.note, System.currentTimeMillis())
        val configure: TaskRequest.Builder.() -> Unit = {
            inputData(input, LabTaskInput.serializer())
            existingTaskPolicy(ExistingTaskPolicy.REPLACE)
            constraints {
                requiresCharging = request.requiresCharging
                if (request.requiresNetwork) requiredNetworkType = NetworkType.CONNECTED
            }
        }
        val taskRequest =
            when (request.trigger) {
                Trigger.Periodic ->
                    TaskRequest.periodic(request.task.id, 15.minutes) {
                        configure()
                        runImmediately()
                    }
                Trigger.Calendar ->
                    TaskRequest.calendar(
                        request.task.id,
                        CronExpression.everyDayAt(LocalTime.now().plusMinutes(2)),
                        configure,
                    )
                Trigger.OnBoot -> TaskRequest.onBoot(request.task.id, configure)
            }
        return DesktopTaskScheduler.enqueue(taskRequest)
    }

    override fun cancel(taskId: TaskId): Boolean = DesktopTaskScheduler.cancel(taskId)

    override fun cancelAll() = DesktopTaskScheduler.cancelAll()

    override fun tasks(): List<TaskInfo> = DesktopTaskScheduler.getAllTasks()

    override fun runLog(): List<String> = LabTask.runLog.tail(RUN_LOG_LINES)

    override suspend fun dryRun(
        task: LabTask,
        note: String,
    ): TaskResult =
        TestTaskRunner.runTask(
            task = task.newTask(),
            taskId = task.id,
            inputData =
                TaskData.of(
                    LabTaskInput("$note (dry run)", System.currentTimeMillis()),
                    LabTaskInput.serializer(),
                ),
        )

    private companion object {
        const val RUN_LOG_LINES = 30
    }
}
