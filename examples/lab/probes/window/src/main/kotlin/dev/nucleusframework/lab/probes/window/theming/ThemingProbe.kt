package dev.nucleusframework.lab.probes.window.theming

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class ThemingProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Design-system windows",
            domain = Domain.Window,
            summary =
                "Does a whole app's worth of components look native to Material 2, Material 3 and Jewel, " +
                    "under their own title bar and border, in light and dark, side by side?",
            modules = DesignSystem.entries.map { it.module } + "decorated-window-core",
            checks =
                listOf(
                    Check(
                        "bars",
                        "Each window's title bar uses its design system's colours (readout matches what is drawn)",
                    ),
                    Check(
                        "dark-light",
                        "Switching a window between Light and Dark recolours its bar, border and every gallery " +
                            "page, and only that window",
                    ),
                    Check("system", "System follows the OS theme live, for all three windows at once"),
                    Check("gradient", "Gradient paints the bar from the accent, starting at the leading edge"),
                    Check("inactive", "Clicking another window switches the bar to its inactive background"),
                    Check(
                        "pages",
                        "Every gallery page opens from its window's navigation and renders whole in light and " +
                            "dark: no blank page, clipped control or unreadable text",
                    ),
                    Check(
                        "m2-elevation",
                        "Material 2: card and surface shadows grow with the elevation, and in dark higher " +
                            "surfaces turn lighter",
                    ),
                    Check(
                        "m2-overlays",
                        "Material 2: dialogs, dropdown and exposed menus, the modal drawer, bottom sheet and " +
                            "backdrop open over the content and close again",
                    ),
                    Check(
                        "m3-expressive",
                        "Material 3 Expressive: shapes morph (loading indicator, toggle buttons, shape list) " +
                            "and button presses spring with the expressive motion",
                    ),
                    Check(
                        "m3-overlays",
                        "Material 3: the dialog, date/time pickers, split-button and FAB menus open over the " +
                            "window and the snackbar slides in",
                    ),
                    Check(
                        "jewel-popups",
                        "Jewel: combo box popups, menus and tooltips open over the window, anchored to their " +
                            "control, and close on Escape or an outside click",
                    ),
                    Check(
                        "jewel-markdown",
                        "Jewel Markdown: editing the left pane updates the preview (tables, alerts, code, " +
                            "images) and Load file opens the native picker",
                    ),
                ),
            keywords =
                listOf(
                    "material2",
                    "material3",
                    "expressive",
                    "jewel",
                    "intellij",
                    "gallery",
                    "showcase",
                    "components",
                    "markdown",
                    "elevation",
                    "title bar style",
                    "dark",
                ),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<ThemingViewModel>()
        val state by vm.state.collectAsState()
        ProbeLayout(
            capabilities = emptyList(),
            controls = {
                DesignSystem.entries.forEach { system ->
                    val config = state.config(system)
                    SubHeading(system.label)
                    Actions {
                        if (system in state.open) {
                            SecondaryAction("Close") { vm.onIntent(ThemingIntent.Close(system)) }
                        } else {
                            PrimaryAction("Open") { vm.onIntent(ThemingIntent.Open(system)) }
                        }
                    }
                    ChoiceRow("Theme", ThemeChoice.entries, config.theme) {
                        vm.onIntent(ThemingIntent.Configure(system, config.copy(theme = it)))
                    }
                    SwitchRow("Accent gradient", config.gradient) {
                        vm.onIntent(ThemingIntent.Configure(system, config.copy(gradient = it)))
                    }
                }
            },
            observed = {
                DesignSystem.entries.forEach { system ->
                    SubHeading(system.label)
                    val report = state.reports[system]
                    if (report == null) {
                        EmptyState(if (system in state.open) "Waiting for the window." else "Closed.")
                    } else {
                        val match = report.matches(state.config(system).theme)
                        val tone = if (match == false) Tone.Error else Tone.Neutral
                        Readout("isDark", report.isDark.toString(), tone = tone)
                        Readout("Title bar", "${report.titleBarBackground} on ${report.titleBarContent}")
                        Readout("…inactive", report.titleBarInactiveBackground)
                        Readout("Window", "border ${report.windowBorder} · background ${report.windowBackground}")
                    }
                }
            },
        )
    }

    companion object {
        val ID = ProbeId("window.theming")
    }
}
