package dev.nucleusframework.lab.probes.system.storeupdates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubSection
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.store.microsoft.StoreUpdateCheck
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class StoreUpdatesProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Store updates",
            domain = Domain.System,
            summary =
                "Does the Microsoft Store tell the Lab a newer version exists, and install it from " +
                    "inside the app with its progress?",
            modules = listOf("microsoft-store"),
            platforms = setOf(Platform.Windows),
            checks =
                listOf(
                    Check(
                        "up-to-date",
                        "On the latest Store version, Check reads UpToDate",
                    ),
                    Check(
                        "available",
                        "With a newer version published, Check reads Available with its version number",
                    ),
                    Check(
                        "mandatory",
                        "An update Partner Center marks mandatory reads isMandatory = true",
                    ),
                    Check(
                        "install",
                        "Install: the Store dialog opens over the Lab, progress climbs through downloading " +
                            "and installing, and Windows restarts the Lab on the new version",
                    ),
                    Check("silent", "Install silently: same, without any Store window"),
                    Check("decline", "Declining the Store dialog ends Cancelled, not an error"),
                    Check("store-page", "Open Store page shows the Lab's page in the Store app"),
                ),
            keywords = listOf("update", "Microsoft Store", "StorePackageUpdate", "mandatory"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<StoreUpdatesViewModel>()
        val state by vm.state.collectAsState()
        val hwnd =
            LocalNucleusWindow.current.unsafe.taoWindow
                ?.nativeHandle ?: 0L
        val ready = state.supported.isAvailable && !state.installing

        ProbeLayout(
            capabilities = listOf(Capability("Store bridge", state.supported, detail = "Windows.Services.Store")),
            controls = {
                Actions {
                    PrimaryAction("Check", enabled = ready) { vm.onIntent(StoreUpdatesIntent.Check) }
                    SecondaryAction("Install", enabled = ready) {
                        vm.onIntent(StoreUpdatesIntent.Install(silent = false, hwnd = hwnd))
                    }
                    SecondaryAction("Install silently", enabled = ready) {
                        vm.onIntent(StoreUpdatesIntent.Install(silent = true, hwnd = hwnd))
                    }
                    SecondaryAction(
                        "Open Store page",
                        enabled = ready,
                    ) { vm.onIntent(StoreUpdatesIntent.OpenStorePage) }
                }
                Hint("Publish a newer version in Partner Center without installing it, then Check here.")
            },
            observed = {
                SubSection("Update") {
                    when (val check = state.check) {
                        null -> EmptyState("Not checked yet.")
                        StoreUpdateCheck.UpToDate -> Readout("Store", "up to date", tone = Tone.Ok)
                        is StoreUpdateCheck.Available -> {
                            Readout("Store", "version ${check.version ?: "?"} available", tone = Tone.Warning)
                            Readout("mandatory", check.isMandatory.toString())
                        }
                    }
                    state.progress.lastOrNull()?.let { Readout("install", it.describe()) }
                }
                SubSection("Calls") {
                    EventLog(state.calls)
                }
            },
        )
    }

    companion object {
        val ID = ProbeId("system.store-updates")
    }
}
