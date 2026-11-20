package dev.nucleusframework.lab.probes.lifecycle.singleinstance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.format.formatTime
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.nucleusframework.lab.probes.lifecycle.launch.LaunchList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class SingleInstanceProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Single instance",
            domain = Domain.Lifecycle,
            summary = "Does a second launch hand over to this instance and quit, instead of opening a second app?",
            modules = listOf("core-runtime", "nucleus-application"),
            checks =
                listOf(
                    Check("held", "Installed app: the lock reads \"held by this process\""),
                    Check("forward", "Launch second instance: it exits 0 within ~2 s, no second window appears"),
                    Check("restore-file", "A restore-request CREATE then DELETE shows under Restore signals"),
                    Check(
                        "front",
                        "This window comes to front — un-minimized if it was minimized — when the second launch " +
                            "arrives",
                    ),
                    Check("dev", "Dev run: the second instance stays up as a separate Lab (expected: no lock in dev)"),
                    Check(
                        "crash",
                        "After force-killing the Lab, the next launch starts normally (stale lock recovered)",
                    ),
                ),
            keywords = listOf("lock", "second instance", "restore"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<SingleInstanceViewModel>()
        val state by vm.state.collectAsState()

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "Lock taken at startup",
                        Availability.of(
                            state.lockTakenByApp,
                        ) { "dev run: nucleusApplication(enableSingleInstance) defaults to off" },
                    ),
                ),
            controls = {
                Actions {
                    PrimaryAction("Launch second instance") { vm.onIntent(SingleInstanceIntent.LaunchSecond) }
                    SecondaryAction("Check lock") { vm.onIntent(SingleInstanceIntent.CheckLock) }
                    if (state.launches.any { it.running }) {
                        SecondaryAction("Kill launched instances") { vm.onIntent(SingleInstanceIntent.KillLaunched) }
                    }
                }
                Hint("To test the front/restore check, minimize this window and launch from a terminal.")
            },
            observed = {
                Readout("Lock identifier", state.lockIdentifier)
                Readout("Lock file", state.lockFile)
                Readout("Restore request file", state.restoreRequestFile)
                Readout(
                    "Lock",
                    state.holderError ?: state.holder?.label,
                    tone =
                        when {
                            state.holderError != null -> Tone.Error
                            state.holder == LockHolder.ThisProcess -> Tone.Ok
                            state.lockTakenByApp -> Tone.Warning
                            else -> Tone.Neutral
                        },
                )
                state.checkedAt?.let { Readout("Checked", formatTime(it), tone = Tone.Muted) }
                SubHeading("Restore signals")
                EventLog(
                    state.restoreSignals.map { it.toLogEntry() },
                    empty = "None — a forwarded launch writes the restore file.",
                )
                LaunchList(state.launches)
            },
        )
    }

    companion object {
        val ID = ProbeId("lifecycle.single-instance")
    }
}
