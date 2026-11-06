package dev.nucleusframework.lab.probes.system.fswatcher

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
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class FsWatcherProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Filesystem watcher",
            domain = Domain.System,
            summary =
                "Does every create, modify, rename and delete under a watched directory " +
                    "arrive once, quickly, with the right kind?",
            modules = listOf("fs-watcher"),
            checks =
                listOf(
                    Check(
                        "kinds",
                        "Each action yields its own kind: Created, Modified, Moved (Debounced) and Removed, on the right path",
                    ),
                    Check(
                        "rename-debounced",
                        "Debounced: a file or directory rename is one Moved from → to, not Created + Modified (#570)",
                    ),
                    Check(
                        "rename-raw",
                        "Raw: the same rename arrives as Removed(old) + Created(new), never as Moved, never dropped",
                    ),
                    Check(
                        "recursive",
                        "With recursive off, nothing under nested/deep is reported; with it on, leaf.txt is",
                    ),
                    Check(
                        "latency",
                        "Raw events land within ~100 ms of the action; Debounced within the 150 ms window plus that",
                    ),
                    Check(
                        "burst",
                        "The 50-file burst shows 50 Created paths, or an Overflow flagged needsRescan, never a silent gap",
                    ),
                    Check(
                        "errors",
                        "A failed action or watcher error shows as an error entry, the watch keeps running",
                    ),
                ),
            keywords = listOf("inotify", "fsevents", "ReadDirectoryChangesW", "notify", "file watch"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<FsWatcherViewModel>()
        val state by vm.state.collectAsState()
        val settings = state.settings

        fun change(next: WatchSettings) = vm.onIntent(FsWatcherIntent.ChangeSettings(next))

        ProbeLayout(
            capabilities =
                listOf(
                    Capability("Backend ${settings.backend} / ${settings.delivery}", state.available),
                    Capability("Watching", watchAvailability(state)),
                ),
            controls = {
                Readout("root", state.root)
                SwitchRow("Recursive", settings.recursive) { change(settings.copy(recursive = it)) }
                ChoiceRow("Delivery", EventDelivery.entries, settings.delivery) { change(settings.copy(delivery = it)) }
                ChoiceRow("Backend", Backend.entries, settings.backend) { change(settings.copy(backend = it)) }
                Hint("Changing a setting restarts the watcher.")
                Actions {
                    if (state.watching) {
                        SecondaryAction("Stop watching") { vm.onIntent(FsWatcherIntent.SetWatching(false)) }
                    } else {
                        PrimaryAction(
                            "Watch",
                            enabled = state.available.isAvailable,
                        ) { vm.onIntent(FsWatcherIntent.SetWatching(true)) }
                    }
                    SecondaryAction("Clear") { vm.onIntent(FsWatcherIntent.Clear) }
                }
                SubHeading("Stimuli")
                Actions {
                    FsAction.entries.forEach { action ->
                        SecondaryAction(action.label) { vm.onIntent(FsWatcherIntent.Perform(action)) }
                    }
                }
                if (!state.watching) {
                    Hint("Not watching: actions run, but nothing will be reported.")
                }
            },
            observed = {
                SubHeading("Actions (newest first)")
                ActionList(state.actions, state.watching)
                if (state.errors.isNotEmpty()) {
                    SubHeading("Watcher errors")
                    EventLog(state.errors.map { LogEntry(it, tone = Tone.Error) })
                }
                SubHeading("Events (${state.events.size}, newest first)")
                EventList(state.events)
            },
        )
    }

    companion object {
        val ID = ProbeId("system.fswatcher")
    }
}

private fun watchAvailability(state: FsWatcherState) =
    when {
        state.watching -> Availability.Available
        state.watchError != null -> Availability.Unavailable(state.watchError)
        else -> Availability.Unavailable("stopped")
    }
