package dev.nucleusframework.lab.probes.input.scroll

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.core.format.signed
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.Counters
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.MonoTable
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.rememberCopyToClipboard
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class ScrollProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Scroll & wheel",
            domain = Domain.Input,
            summary = "Do wheel notches and trackpad swipes scroll with the right sign, magnitude, event type and fps?",
            modules = listOf("decorated-window-tao"),
            checks =
                listOf(
                    Check(
                        "notch",
                        "One wheel notch scrolls 10 dp: 'px / event' reads 10 × density for a single notch (#653)",
                    ),
                    Check(
                        "pan",
                        "A trackpad swipe shows PanStart, PanMove…, exactly one PanEnd — no Scroll — unless trackpadPanEvents=false (#654)",
                    ),
                    Check(
                        "sign",
                        "Fingers up grow the vertical offset, fingers left grow the horizontal one, on every OS (#652)",
                    ),
                    Check(
                        "momentum",
                        "Momentum keeps scrolling after lift-off; PanEnd lands ≈0 ms (momentum) or ≈150 ms (grace) after the last move",
                    ),
                    Check(
                        "fps",
                        "Render fps stays at the display refresh while scrolling, not at the wheel rate (~20)",
                    ),
                    Check("split", "Two quick swipes are logged as two gestures, never merged into one"),
                ),
            keywords = listOf("wheel", "trackpad", "pan", "momentum", "smooth scroll"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<ScrollViewModel>()
        val state by vm.state.collectAsState()
        val density = LocalDensity.current.density
        val copy = rememberCopyToClipboard()
        LaunchedEffect(vm) {
            vm.effects.collect { effect ->
                if (effect is ScrollEffect.Copy) copy(effect.text)
            }
        }

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "Trackpad Pan events",
                        if (state.panEventsEnabled) {
                            Availability.Available
                        } else {
                            Availability.Unavailable(
                                "-Dnucleus.tao.trackpadPanEvents=false: AWT-style Scroll",
                            )
                        },
                        detail = "Pan/Scale come from the macOS trackpad; wheels and other touchpads send Scroll",
                    ),
                ),
            controls = {
                Readout("Density", "${density.fmt()} · 1 wheel unit = ${(PAN_DP_PER_WHEEL_UNIT * density).fmt(0)} px")
                Actions {
                    PrimaryAction(
                        "Copy gestures (TSV)",
                        enabled = state.gestures.isNotEmpty(),
                    ) { vm.onIntent(ScrollIntent.CopyTsv) }
                    SecondaryAction("Reset") { vm.onIntent(ScrollIntent.Reset) }
                }
                Hint("Meter — scroll here with the wheel and the trackpad.")
                ScrollMeterArea(vm)
                Hint("Sign — vertical: fingers UP ⇒ grows · horizontal: fingers LEFT ⇒ grows.")
                SignStrips()
            },
            observed = {
                Readout(
                    "Live",
                    "rawΔy ${state.liveRawY.signed(3)} · offset ${state.offsetPx}/${state.maxOffsetPx} px · " +
                        "render ${state.fps} fps",
                    tone = if (state.fps in 1..30) Tone.Warning else Tone.Neutral,
                )
                with(state.counters) {
                    Counters(
                        listOf(
                            "Scroll" to scroll,
                            "PanStart" to panStart,
                            "PanMove" to panMove,
                            "PanEnd" to panEnd,
                            "ScaleStart" to scaleStart,
                            "ScaleChange" to scaleChange,
                            "ScaleEnd" to scaleEnd,
                        ),
                    )
                    if (panStart != panEnd) Readout("Open pan gestures", "${panStart - panEnd}", tone = Tone.Warning)
                }
                SubHeading("Gestures (newest first)")
                MonoTable(
                    header = listOf("#", "events", "Σ rawΔy", "px", "ms", "max|Δy|", "px/evt", "fps"),
                    rows =
                        state.gestures.map { g ->
                            listOf(
                                "${g.index}",
                                "${g.events}",
                                g.rawSum.y.signed(),
                                "%+d".format(g.pxScrolled),
                                "${g.durationMs}",
                                g.maxRawAbsY.fmt(),
                                if (g.events == 0) {
                                    "-"
                                } else {
                                    (g.pxScrolled.toFloat() / g.events).signed(1)
                                },
                                "${g.fps}",
                            )
                        },
                    widths = listOf(32.dp, 56.dp, 72.dp, 64.dp, 48.dp, 64.dp, 64.dp, 40.dp),
                )
                SubHeading("Events at the root (newest first, ${Platform.Current})")
                EventLog(state.lines.map(::LogEntry), newestFirst = false, max = 30)
            },
        )
    }

    companion object {
        val ID = ProbeId("input.scroll")
    }
}
