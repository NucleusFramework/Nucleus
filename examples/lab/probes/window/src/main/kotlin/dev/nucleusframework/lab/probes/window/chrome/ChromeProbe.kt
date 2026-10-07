package dev.nucleusframework.lab.probes.window.chrome

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
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.window.ControlButtonsDirection
import dev.nucleusframework.window.WindowsBackdropStyle
import dev.nucleusframework.window.WindowsBackdropTier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class ChromeProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Window chrome",
            domain = Domain.Window,
            summary =
                "Does every chrome primitive — scaffold placement, title bars, controls, backdrops, glass, drag " +
                    "areas — apply live to a real window?",
            modules = listOf("decorated-window-tao", "decorated-window-core", "decorated-window-jewel"),
            checks =
                listOf(
                    Check(
                        "placement",
                        "Overlay runs the content under the bar up to the top edge; Docked puts an opaque band above it",
                    ),
                    Check(
                        "autohide",
                        "Overlay + auto-hide: entering fullscreen removes the bar, leaving brings it back",
                    ),
                    Check(
                        "controls",
                        "Minimize / maximize / close work and sit on the side the controls direction asks for",
                    ),
                    Check(
                        "drag",
                        "The bar moves the window and double-click maximizes; the content area does so only with 'drag from content'",
                    ),
                    Check(
                        "fillcenter",
                        "Stock bar + FillCenter stretches the centre field between leading and trailing items",
                    ),
                    Check(
                        "backdrop",
                        "Windows: Mica / Acrylic / Mica Alt show through unpainted areas; a tint change re-tints without a blink",
                    ),
                    Check(
                        "glass",
                        "macOS: the sidebar shows the system material; the large corner radius rounds the window",
                    ),
                ),
            keywords = listOf("title bar", "scaffold", "mica", "acrylic", "glass", "traffic lights", "drag"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<ChromeViewModel>()
        val state by vm.state.collectAsState()
        val c = state.config

        fun set(transform: ChromeConfig.() -> ChromeConfig) = vm.onIntent(ChromeIntent.SetConfig(c.transform()))

        ProbeLayout(
            capabilities = capabilities(),
            controls = {
                Actions {
                    if (state.sessionOpen) {
                        SecondaryAction("Close window") { vm.onIntent(ChromeIntent.Close) }
                    } else {
                        PrimaryAction("Open chrome lab window") { vm.onIntent(ChromeIntent.Open) }
                    }
                }
                SubHeading("WindowScaffold")
                ChoiceRow("Placement", BarPlacement.entries, c.placement) { set { copy(placement = it) } }
                SwitchRow(
                    "Auto-hide in fullscreen",
                    c.autoHideInFullscreen,
                    enabled =
                        c.placement == BarPlacement.Overlay,
                ) { set { copy(autoHideInFullscreen = it) } }
                SwitchRow(
                    "Pass through to content",
                    c.passThroughToContent,
                    enabled =
                        c.placement == BarPlacement.Overlay,
                ) { set { copy(passThroughToContent = it) } }
                SwitchRow("Drag from content", c.dragFromContent) { set { copy(dragFromContent = it) } }
                SubHeading("Title bar")
                ChoiceRow("Bar", BarKind.entries, c.bar) { set { copy(bar = it) } }
                SwitchRow(
                    "FillCenter layout",
                    c.fillCenter,
                    enabled = c.bar == BarKind.Stock,
                ) { set { copy(fillCenter = it) } }
                ChoiceRow("Controls side", ControlButtonsDirection.entries, c.controlsDirection) {
                    set { copy(controlsDirection = it) }
                }
                SwitchRow("newFullscreenControls", c.newFullscreenControls, enabled = c.bar == BarKind.Stock) {
                    set { copy(newFullscreenControls = it) }
                }
                SubHeading("Appearance")
                ChoiceRow("Theme", AppearanceChoice.entries, c.appearance) { set { copy(appearance = it) } }
                SwitchRow("macOS large corner radius", c.largeCorner) { set { copy(largeCorner = it) } }
                SwitchRow("macOS glass sidebar", c.glassSidebar) { set { copy(glassSidebar = it) } }
                SubHeading("Windows backdrop")
                ChoiceRow("Style", WindowsBackdropStyle.entries, c.backdrop) { set { copy(backdrop = it) } }
                ChoiceRow("Tint", TintChoice.entries, c.tint, name = { it.label }) { set { copy(tint = it) } }
                ChoiceRow("Tier", WindowsBackdropTier.entries, c.tier) { set { copy(tier = it) } }
            },
            observed = {
                if (!state.sessionOpen) EmptyState("Window closed.")
                SubHeading("LocalWindowChromeInsets (inside the window)")
                Readout("Title bar height", state.readback?.let { "${it.titleBarHeightDp} dp" })
                Readout("Controls insets", state.readback?.controlsInsets)
                Readout("Layout direction", state.readback?.layoutDirection)
                SubHeading("Window")
                Readout("Snapshot", state.snapshot?.describe())
                Readout("Content drag presses", state.contentDragPresses.toString())
                Readout(
                    "Controls drawn by",
                    if (Platform.Current == Platform.MacOS) {
                        "AppKit (traffic lights)"
                    } else {
                        "Compose WindowControls (custom bar) / the stock bar"
                    },
                )
                if (c.backdropActive && Platform.Current != Platform.Windows) {
                    Readout("Backdrop", "requested but this is not Windows: nothing should change", tone = Tone.Muted)
                }
            },
        )
    }

    private fun capabilities(): List<Capability> {
        val os = Platform.Current
        return listOf(
            Capability(
                "Windows backdrop",
                Availability.of(os == Platform.Windows) {
                    "Windows 11 materials (Windows 10 degrades to acrylic); no-op on ${os.name}"
                },
                "The tier DWM ended up using is not reported back by the API",
            ),
            Capability(
                "Glass region",
                Availability.of(os == Platform.MacOS) { "NSVisualEffectView regions are macOS only" },
            ),
            Capability("Large corner radius", Availability.of(os == Platform.MacOS) { "macOS 26 window corner only" }),
            Capability(
                "Native traffic lights",
                Availability.of(os == Platform.MacOS) {
                    "the app draws WindowControls itself on ${os.name}"
                },
            ),
        )
    }

    companion object {
        val ID = ProbeId("window.chrome")
    }
}
