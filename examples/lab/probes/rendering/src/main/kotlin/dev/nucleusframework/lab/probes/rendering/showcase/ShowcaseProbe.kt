package dev.nucleusframework.lab.probes.rendering.showcase

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SpecimenFrame
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.probes.rendering.common.FrameMeter
import dev.nucleusframework.lab.probes.rendering.common.FrameRateReadout
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class ShowcaseProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Showcase scene",
            domain = Domain.Rendering,
            summary =
                "Does a heavy blurred, animated scene hold the display's refresh rate, " +
                    "and go idle when it stops?",
            modules = listOf("decorated-window-tao"),
            checks =
                listOf(
                    Check("smooth", "The mesh drifts with no stutter; fps sits at the display's refresh rate (60/120)"),
                    Check(
                        "blur",
                        "Blobs are soft gradients; turning blur off shows hard-edged circles, no artefacts at the edges",
                    ),
                    Check(
                        "glow",
                        "The cursor glow follows the pointer with no lag and disappears when the pointer leaves",
                    ),
                    Check(
                        "idle",
                        "Pausing the animation drops the scene's fps to 0 (no frames are produced while nothing moves)",
                    ),
                    Check(
                        "resize",
                        "Resizing the window keeps the scene filled and the card centred, without a white flash",
                    ),
                ),
            keywords = listOf("fps", "blur", "animation", "stress", "fancy"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<ShowcaseViewModel>()
        val state by vm.state.collectAsState()
        val meter = remember { FrameMeter() }

        ProbeLayout(
            capabilities = emptyList(),
            controls = {
                SwitchRow("Animate", state.animating) { vm.onIntent(ShowcaseIntent.ToggleAnimation) }
                SwitchRow("Blur (120 dp)", state.blur) { vm.onIntent(ShowcaseIntent.ToggleBlur) }
                SwitchRow("Cursor glow", state.cursorGlow) { vm.onIntent(ShowcaseIntent.ToggleCursorGlow) }
                SubHeading("Blobs")
                Actions {
                    state.blobs.forEachIndexed { index, on ->
                        SwitchRow("Blob ${index + 1}", on) { vm.onIntent(ShowcaseIntent.ToggleBlob(index)) }
                    }
                }
            },
            observed = {
                FrameRateReadout("Scene redraws", meter)
                Readout("Clicks", state.clicks.toString())
                Readout("Blobs on", "${state.blobs.count { it }} / ${state.blobs.size}")
                Hint("Counted from the background's draw pass: what the compositor actually redrew.")
            },
            wide = {
                SpecimenFrame(height = 380.dp) {
                    FancyScene(
                        state = state,
                        meter = meter,
                        onClick = { vm.onIntent(ShowcaseIntent.Click) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        )
    }

    companion object {
        val ID = ProbeId("rendering.showcase")
    }
}
