package dev.nucleusframework.lab.probes.system.flatpakupdates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.flatpak.FlatpakUpdateStatus
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
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class FlatpakUpdatesProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Flatpak updates",
            domain = Domain.System,
            summary =
                "Does the Flatpak portal tell the Lab a newer commit waits on its remote (Flathub or any " +
                    "other), install it from inside the app, and restart the Lab on it?",
            modules = listOf("flatpak"),
            platforms = setOf(Platform.Linux),
            checks =
                listOf(
                    Check(
                        "not-flatpak",
                        "Outside a Flatpak (gradlew run, deb, AppImage) the capability reads \"not a Flatpak\" " +
                            "and no call reaches the portal",
                    ),
                    Check(
                        "up-to-date",
                        "Installed from the remote's latest commit, Watch reads up to date on the portal's first check",
                    ),
                    Check(
                        "available",
                        "With a newer commit published to the remote, Watch reads available with both commits",
                    ),
                    Check(
                        "install",
                        "Install: the portal's \"Update Nucleus Lab?\" dialog opens over the Lab, progress climbs, " +
                            "the call ends Completed and Watch reads installed",
                    ),
                    Check(
                        "deny",
                        "Denying that dialog fails with UpdateDenied, and so does every later install until it is " +
                            "allowed again in the desktop's app settings",
                    ),
                    Check("restart", "Restart: the Lab comes back on the new commit, in a single window"),
                    Check(
                        "background",
                        "An update installed by `flatpak update` or the store while the Lab runs reads installed",
                    ),
                    Check("store-page", "Open store page shows the Lab in GNOME Software or Discover"),
                ),
            keywords = listOf("update", "Flatpak", "Flathub", "UpdateMonitor", "portal"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<FlatpakUpdatesViewModel>()
        val state by vm.state.collectAsState()
        val window = LocalNucleusWindow.current.unsafe.taoWindow
        val ready = state.supported.isAvailable && !state.installing

        ProbeLayout(
            capabilities =
                listOf(Capability("Flatpak portal", state.supported, detail = "org.freedesktop.portal.Flatpak")),
            controls = {
                Actions {
                    PrimaryAction(
                        if (state.watching) "Stop watching" else "Watch",
                        enabled = state.supported.isAvailable,
                    ) {
                        vm.onIntent(FlatpakUpdatesIntent.ToggleWatch)
                    }
                    SecondaryAction("Install", enabled = ready) { vm.onIntent(FlatpakUpdatesIntent.Install(window)) }
                    SecondaryAction("Restart", enabled = ready) { vm.onIntent(FlatpakUpdatesIntent.Restart) }
                    SecondaryAction("Open store page", enabled = state.supported.isAvailable) {
                        vm.onIntent(FlatpakUpdatesIntent.OpenStorePage(window))
                    }
                }
                Hint(
                    "The portal checks every 30 minutes. To test, restart it faster: " +
                        "/usr/libexec/flatpak-portal --replace --poll-timeout=10 (see docs/internals/flathub.md).",
                )
            },
            observed = {
                SubSection("Update") {
                    Readout("app id", state.appId ?: "none")
                    when (val status = state.status) {
                        null ->
                            EmptyState(
                                if (state.watching) "Waiting for the portal's next check." else "Not watching.",
                            )
                        else ->
                            Readout(
                                "remote",
                                status.describe(),
                                tone = if (status is FlatpakUpdateStatus.UpToDate) Tone.Ok else Tone.Warning,
                            )
                    }
                    state.progress?.let { Readout("install", it.describe()) }
                }
                SubSection("Calls") {
                    EventLog(state.calls)
                }
            },
        )
    }

    companion object {
        val ID = ProbeId("system.flatpak-updates")
    }
}
