package dev.nucleusframework.lab.probes.shell.badge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import dev.nucleusframework.lab.designsystem.NumberFieldRow
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.nucleusframework.launcher.windows.BadgeGlyph
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class BadgeProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Badge",
            domain = Domain.Shell,
            summary = "Does the count on the Dock tile / taskbar button / launcher icon follow what the app sets?",
            modules = listOf("launcher-windows", "launcher-linux", "notification-macos"),
            checks =
                listOf(
                    Check("count", "Setting 5 shows a “5” on the app's Dock tile / taskbar button / launcher icon"),
                    Check("overflow", "Setting 1234 is rendered legibly (Windows caps at 99+)"),
                    Check("clear", "Clear removes the badge entirely, no empty bubble left behind"),
                    Check("ramp", "Ramp to 20 ends on 20 with no step skipped or stuck"),
                    Check("glyph", "Windows: every glyph renders as its icon, and Clear removes it"),
                    Check("readback", "macOS: the read-back count equals the last value set"),
                ),
            keywords = listOf("dock", "taskbar", "unity", "count", "glyph"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<BadgeViewModel>()
        val state by vm.state.collectAsState()
        var count by rememberSaveable { mutableStateOf<Int?>(5) }
        var glyph by rememberSaveable { mutableStateOf(BadgeGlyph.NEW_MESSAGE) }
        val enabled = state.availability.isAvailable

        ProbeLayout(
            capabilities =
                listOfNotNull(
                    Capability(state.backend.label, state.availability, detail = state.target),
                    state.prepared
                        ?.let {
                            Capability(
                                "initialize()",
                                if (it.ok) Availability.Available else Availability.Unavailable(it.error.orEmpty()),
                            )
                        }.takeIf { state.backend == BadgeBackend.WindowsTaskbar },
                ),
            controls = {
                NumberFieldRow("Count", count) { count = it }
                Actions {
                    PrimaryAction(
                        "Set count",
                        enabled = enabled && count != null,
                    ) { count?.let { vm.onIntent(BadgeIntent.SetCount(it)) } }
                    SecondaryAction("Clear", enabled = enabled) { vm.onIntent(BadgeIntent.Clear) }
                    SecondaryAction("Ramp 1 → 20", enabled = enabled) { vm.onIntent(BadgeIntent.Ramp(20)) }
                    SecondaryAction("1234", enabled = enabled) { vm.onIntent(BadgeIntent.SetCount(1234)) }
                }
                if (state.supportsGlyphs) {
                    SubHeading("Glyph (Windows)")
                    ChoiceRow("Glyph", BadgeGlyph.entries, glyph, name = { it.value }) { glyph = it }
                    Actions {
                        PrimaryAction(
                            "Set glyph",
                            enabled = enabled,
                        ) { vm.onIntent(BadgeIntent.SetGlyph(glyph)) }
                    }
                }
                if (state.canReadBack) {
                    Actions { SecondaryAction("Read back", enabled = enabled) { vm.onIntent(BadgeIntent.ReadBack) } }
                }
            },
            observed = {
                Readout("Target", state.target)
                Readout("Last applied", state.requested ?: "nothing (cleared)")
                Readout(
                    "OS reports",
                    when {
                        !state.canReadBack -> "no read API on this platform — check the icon itself"
                        else -> state.readBack?.toString()
                    },
                    tone =
                        when {
                            !state.canReadBack -> Tone.Muted
                            state.readBack != null &&
                                state.requested?.toIntOrNull()?.let { it != state.readBack } == true -> Tone.Error
                            else -> Tone.Neutral
                        },
                )
                SubHeading("Calls")
                EventLog(state.calls.map { it.toLogEntry() }, empty = "No call made yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("shell.badge")
    }
}
