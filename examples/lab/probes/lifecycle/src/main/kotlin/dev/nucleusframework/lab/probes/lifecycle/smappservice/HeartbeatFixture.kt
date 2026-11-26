package dev.nucleusframework.lab.probes.lifecycle.smappservice

import dev.nucleusframework.lab.core.LabLog
import dev.nucleusframework.lab.core.fixture.Fixture
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import java.time.Instant

/**
 * What the Lab's launch agent runs: one line appended to [log], then exit. The probe tails
 * that log, so a run of the agent while the Lab is closed is still visible afterwards.
 */
@ContributesIntoSet(AppScope::class)
@Inject
class HeartbeatFixture : Fixture {
    override val id: String = ID
    override val title: String = "Launch agent heartbeat"
    override val description: String =
        "Appends a line to ${log.file.fileName} and exits 0. " +
            "launchd runs it every minute once the agent is registered."

    override fun run(args: Array<String>) {
        log.append("${Instant.now()} pid=${ProcessHandle.current().pid()} args=${args.joinToString(" ")}")
    }

    companion object {
        const val ID = "lifecycle.agent-heartbeat"

        /** Label of the agent declared in the app's `macOS { launchAgents { } }`. */
        const val AGENT_LABEL = "dev.nucleusframework.lab.heartbeat"

        val log = LabLog("agent-heartbeat")
    }
}
