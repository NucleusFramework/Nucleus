package dev.nucleusframework.lab.probes.lifecycle.scheduler

import dev.nucleusframework.lab.core.fixture.Fixture
import dev.nucleusframework.lab.core.fixture.FixtureVariant
import dev.nucleusframework.scheduler.DesktopBootReceiver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlin.system.exitProcess

/**
 * The process the OS scheduler starts: `… --nucleus-scheduler-run <taskId>`. Runs the task
 * through [DesktopBootReceiver] exactly as an app's `main` would, then exits.
 */
@ContributesIntoSet(AppScope::class)
@Inject
class ScheduledTaskFixture : Fixture {
    override val id: String = ID
    override val title: String = "Scheduled task run"
    override val description: String =
        "What Task Scheduler / launchd / systemd invoke: DesktopBootReceiver.handle() runs the task, records its " +
            "result and exits."

    override val variants: List<FixtureVariant> =
        LabTask.entries.map { FixtureVariant(it.id.value, args = listOf(SCHEDULER_ARG, it.id.value)) }

    override val exitCodes: Map<Int, String> =
        mapOf(
            EXIT_NOT_A_SCHEDULER_RUN to "started without --nucleus-scheduler-run",
        )

    override fun run(args: Array<String>) {
        if (!DesktopBootReceiver.isSchedulerInvocation(args)) exitProcess(EXIT_NOT_A_SCHEDULER_RUN)
        DesktopBootReceiver.handle(args, LabTask.registry())
    }

    companion object {
        const val ID = "lifecycle.scheduled-task"
        const val SCHEDULER_ARG = "--nucleus-scheduler-run"
        private const val EXIT_NOT_A_SCHEDULER_RUN = 3
    }
}
