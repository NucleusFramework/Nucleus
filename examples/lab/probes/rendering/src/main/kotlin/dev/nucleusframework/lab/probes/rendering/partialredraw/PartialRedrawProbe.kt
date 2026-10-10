package dev.nucleusframework.lab.probes.rendering.partialredraw

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SliderRow
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.rememberCopyToClipboard
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class PartialRedrawProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Partial redraw",
            domain = Domain.Rendering,
            summary = "Is only what changed repainted and presented, and is an idle window left alone?",
            modules = listOf("decorated-window-tao"),
            checks =
                listOf(
                    Check("flags", "The readouts match how the Lab was launched (-D flags, app properties, patch)"),
                    Check(
                        "blink",
                        "With tint on, the blinking square alone flashes red; the rest of the window never does",
                    ),
                    Check("idle", "Idle scene with tint on: no flash anywhere while nothing moves"),
                    Check("sweep", "The sweep tints its band only; the static panel's draw count stays put meanwhile"),
                    Check(
                        "full",
                        "Full-frame colour tints the whole sample, and switching back returns to small flashes",
                    ),
                ),
            keywords = listOf("damage", "tint", "verify", "buffer age", "present"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<PartialRedrawViewModel>()
        val state by vm.state.collectAsState()
        val copy = rememberCopyToClipboard()
        LaunchedEffect(vm) {
            vm.effects.collect { effect ->
                when (effect) {
                    is PartialRedrawEffect.Copy -> copy(effect.text)
                }
            }
        }
        val counters = remember { DamageCounters() }
        val config = state.config

        ProbeLayout(
            capabilities =
                listOfNotNull(
                    config?.let {
                        Capability(
                            "Partial redraw",
                            Availability.of(it.enabled) { "off — launch with -Dnucleus.tao.partialRedraw=true" },
                        )
                    },
                    config?.let {
                        Capability(
                            "Compose layer-damage patch",
                            Availability.of(it.composePatched) {
                                "missing: nucleusOptimization { partialRedraw } not applied, every frame is full"
                            },
                        )
                    },
                    config?.let {
                        Capability(
                            "Tint",
                            Availability.of(it.tint) {
                                if (it.verify) "suppressed by verify" else "launch with .tint=true"
                            },
                        )
                    },
                ),
            controls = {
                ChoiceRow("Scene", DamageScene.entries, state.scene, name = { it.label }) {
                    vm.onIntent(PartialRedrawIntent.SelectScene(it))
                }
                Hint(state.scene.expectation)
                SliderRow(
                    "Interval",
                    state.intervalMillis.toFloat(),
                    valueRange =
                        PartialRedrawReducer.MIN_INTERVAL.toFloat()..PartialRedrawReducer.MAX_INTERVAL.toFloat(),
                    format = { "${it.toLong()} ms" },
                ) { vm.onIntent(PartialRedrawIntent.SetInterval(it.toLong())) }
                Actions {
                    SecondaryAction("Re-read switches") { vm.onIntent(PartialRedrawIntent.Refresh) }
                    SecondaryAction("Copy relaunch flags") { vm.onIntent(PartialRedrawIntent.CopyRelaunchFlags) }
                }
                SubHeading("What tint shows")
                Hint(
                    "Every other presented frame flashes what it repainted in translucent red, as Android's " +
                        "\"show GPU view updates\" does. A flash bigger than what moved means the damage is " +
                        "over-reported (a layer without a clip, a shadow, an uncertain frame); no flash on an " +
                        "idle window means no frame was presented at all. Switches are read at startup: change " +
                        "them by relaunching, or from the Fixtures probe for the torture scenes.",
                )
            },
            observed = {
                Readout(
                    "enabled",
                    config?.enabled?.toString(),
                    tone = if (config?.enabled == true) Tone.Ok else Tone.Muted,
                )
                Readout("decided by", config?.source?.name)
                Readout(
                    "Compose patched",
                    config?.composePatched?.toString(),
                    tone = if (config?.enabled == true && !config.composePatched) Tone.Warning else Tone.Neutral,
                )
                Readout(".debug", config?.debug?.toString())
                Readout(".tint", config?.tint?.toString())
                Readout(".verify", config?.verify?.toString())
                Readout(".verify.dump", config?.verifyDump)
                DamageReadouts(counters)
            },
            wide = { DamageSample(state.scene, state.intervalMillis, counters) },
        )
    }

    companion object {
        val ID = ProbeId("rendering.partial-redraw")
    }
}
