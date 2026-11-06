package dev.nucleusframework.lab.probes.system.appearance

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.format.argbHex
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ColorSwatch
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.HSpacer
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.nucleusframework.systemcolor.isSystemInHighContrast
import dev.nucleusframework.systemcolor.systemAccentColor
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class AppearanceProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Appearance",
            domain = Domain.System,
            summary = "Does the app follow the OS theme, accent colour and contrast setting live?",
            modules = listOf("darkmode-detector", "system-color"),
            checks =
                listOf(
                    Check("dark-push", "Switching the OS theme pushes a dark= change within a second, no restart"),
                    Check("dark-match", "Polled and pushed values agree after the switch"),
                    Check("accent", "Changing the OS accent colour updates the swatch and the Lab's own palette"),
                    Check("contrast", "Toggling high contrast / increase contrast flips highContrast"),
                    Check("thread", "No change in the timeline is flagged as delivered off the UI thread"),
                ),
            keywords = listOf("dark mode", "theme", "accent", "contrast"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<AppearanceViewModel>()
        val state by vm.state.collectAsState()

        val accent = systemAccentColor()
        val highContrast = isSystemInHighContrast()
        LaunchedEffect(accent, highContrast) {
            vm.onIntent(AppearanceIntent.ComposableReport(accent?.toArgb()?.toLong()?.and(0xFFFFFFFFL), highContrast))
        }

        ProbeLayout(
            capabilities =
                listOf(
                    Capability("Dark mode listener", state.darkModeDetector),
                    Capability("Accent colour", state.accentSupported),
                ),
            controls = {
                Actions {
                    SecondaryAction("Poll isDark()") { vm.onIntent(AppearanceIntent.Poll) }
                    SecondaryAction("Clear history") { vm.onIntent(AppearanceIntent.ClearHistory) }
                }
                Hint("Change the setting in the OS, then compare below.")
            },
            observed = {
                Readout("isDark() (polled)", state.darkPolled?.toString())
                val disagrees = state.darkPushed != null && state.darkPushed != state.darkPolled
                Readout(
                    "listener (pushed)",
                    state.darkPushed?.toString() ?: "no push yet",
                    tone = if (disagrees) Tone.Warning else Tone.Neutral,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Readout("systemAccentColor()", state.accentArgb?.argbHex() ?: "null", Modifier.weight(1f))
                    state.accentArgb?.let {
                        HSpacer()
                        ColorSwatch(Color(it.toInt()))
                    }
                }
                Readout("isSystemInHighContrast()", state.highContrast?.toString())
                SubHeading("Changes")
                EventLog(state.history.map { it.toLogEntry() }, empty = "None observed yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("system.appearance")
    }
}
