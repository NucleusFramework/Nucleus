package dev.nucleusframework.lab.probes.shell.appimage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.core.runtime.AppImageIntegrationStatus
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class AppImageIntegrationProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "AppImage desktop integration",
            domain = Domain.Shell,
            summary =
                "Does the AppImage install its own desktop entry and icons on consent, so the desktop " +
                    "shows the Lab's icon and lists it among the applications?",
            modules = listOf("core-runtime"),
            platforms = setOf(Platform.Linux),
            checks =
                listOf(
                    Check(
                        "integrate",
                        "From the AppImage, Integrate installs ~/.local/share/applications/<name>.desktop " +
                            "and the status turns to integrated",
                    ),
                    Check(
                        "launcher",
                        "The Lab appears in the application grid and launches the AppImage from there",
                    ),
                    Check(
                        "icon",
                        "After a relaunch from the entry, the dock / taskbar shows the Lab icon, not a generic one",
                    ),
                    Check(
                        "stale",
                        "After moving the AppImage and relaunching it, the status is stale and Integrate repairs Exec=",
                    ),
                    Check(
                        "managed-elsewhere",
                        "With the Lab's deb or rpm installed, the status is managed elsewhere and Integrate refuses",
                    ),
                ),
            keywords = listOf("appimage", "desktop entry", "xdg", "icon", "gnome", "launcher"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<AppImageIntegrationViewModel>()
        val state by vm.state.collectAsState()
        val status = state.status

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "AppImage",
                        when (status) {
                            null -> Availability.Unknown
                            AppImageIntegrationStatus.NotAppImage ->
                                Availability.Unavailable(
                                    "run the packaged AppImage (:examples:lab:app:packageAppImage)",
                                )
                            else -> Availability.Available
                        },
                    ),
                ),
            controls = {
                Actions {
                    PrimaryAction("Integrate", enabled = status?.canIntegrate == true) {
                        vm.onIntent(AppImageIntegrationIntent.Integrate)
                    }
                    SecondaryAction("Refresh status") { vm.onIntent(AppImageIntegrationIntent.Refresh) }
                }
            },
            observed = {
                Readout("Status", status?.label())
                Readout("Desktop entry", status?.desktopFile)
                Readout("APPIMAGE", System.getenv("APPIMAGE"))
                SubHeading("Calls")
                EventLog(state.calls.map { it.toLogEntry() }, empty = "No call made yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("shell.appimage-integration")
    }
}
