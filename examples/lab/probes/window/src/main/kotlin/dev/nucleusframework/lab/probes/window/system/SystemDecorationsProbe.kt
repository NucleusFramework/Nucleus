package dev.nucleusframework.lab.probes.window.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class SystemDecorationsProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "System decorations",
            domain = Domain.Window,
            summary =
                "Do Window and DialogWindow get the platform's own title bar and frame (#841), with " +
                    "no Nucleus chrome left over?",
            modules = listOf("nucleus-application", "decorated-window-tao"),
            checks =
                listOf(
                    Check(
                        "native-bar",
                        "macOS shows the standard AppKit title bar; Windows the system caption; Linux the " +
                            "window manager's frame (X11, KWin) or GTK's title bar (GNOME on Wayland)",
                    ),
                    Check("drag", "Dragging the title bar moves the window; double-clicking it maximizes"),
                    Check(
                        "resize",
                        "Every edge and corner of the native frame resizes; no extra band inside the content",
                    ),
                    Check(
                        "target",
                        "A press at the very top-left of the Target box reports about 0, 0 dp — the content starts below the bar",
                    ),
                    Check("fullscreen", "Native fullscreen and back keep the title bar, buttons and content intact"),
                    Check(
                        "titlebar",
                        "With the Nucleus TitleBar on, it shows its text but no window controls and reserves no room for them",
                    ),
                    Check("snap", "Windows: hovering maximize shows Snap Layouts; Alt+Space opens the system menu"),
                    Check("dialog", "The DialogWindow has the native dialog title bar and stays over its window"),
                ),
            keywords = listOf("native", "title bar", "ssd", "csd", "kwin", "gnome", "appkit", "window", "#841"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<SystemDecorationsViewModel>()
        val state by vm.state.collectAsState()

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "Decorations by",
                        Availability.Available,
                        detail = decoratorDescription(),
                    ),
                ),
            controls = {
                SubHeading("Window")
                Actions {
                    if (SystemSurface.Window in state.open) {
                        SecondaryAction(
                            "Close window",
                        ) { vm.onIntent(SystemDecorationsIntent.Close(SystemSurface.Window)) }
                    } else {
                        PrimaryAction("Open Window") { vm.onIntent(SystemDecorationsIntent.Open(SystemSurface.Window)) }
                    }
                }
                SwitchRow("Nucleus TitleBar inside", state.withTitleBar) {
                    vm.onIntent(SystemDecorationsIntent.SetWithTitleBar(it))
                }
                SubHeading("Dialog")
                Actions {
                    if (SystemSurface.Dialog in state.open) {
                        SecondaryAction(
                            "Close dialog",
                        ) { vm.onIntent(SystemDecorationsIntent.Close(SystemSurface.Dialog)) }
                    } else {
                        PrimaryAction(
                            "Open DialogWindow",
                        ) { vm.onIntent(SystemDecorationsIntent.Open(SystemSurface.Dialog)) }
                    }
                }
            },
            observed = {
                Readout("Open", state.open.joinToString { it.name }.ifEmpty { null })
                Readout("Target presses", state.targetPresses.toString())
                Readout("Last press", state.lastPress)
            },
        )
    }

    /** Who is expected to draw the frame on this session. */
    private fun decoratorDescription(): String =
        when (Platform.Current) {
            Platform.MacOS -> "AppKit (standard title bar)"
            Platform.Windows -> "Win32 caption and frame (DWM)"
            Platform.Linux ->
                if (Platform.isWayland) {
                    "the compositor if it offers server-side decorations (KWin), else GTK's own title bar"
                } else {
                    "the X11 window manager"
                }
            else -> "the platform"
        }

    companion object {
        val ID = ProbeId("window.system-decorations")
    }
}
