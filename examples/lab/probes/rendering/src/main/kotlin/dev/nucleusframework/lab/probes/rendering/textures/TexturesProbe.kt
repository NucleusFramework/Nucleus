package dev.nucleusframework.lab.probes.rendering.textures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.probes.rendering.common.FrameMeter
import dev.nucleusframework.lab.probes.rendering.common.FrameRateReadout
import dev.nucleusframework.lab.probes.rendering.common.rememberRecompositionCounter
import dev.nucleusframework.window.tao.TaoStandalonePopup
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.isTaoStandalonePopupAvailable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class TexturesProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "GPU textures",
            domain = Domain.Rendering,
            summary =
                "Do external GPU buffers (D3D11 / IOSurface / DMA-BUF) and the scene's " +
                    "own GPU context composite with no copy and no recomposition?",
            modules = listOf("decorated-window-tao"),
            checks =
                listOf(
                    Check(
                        "animate",
                        "Every texture box animates in step, at the display's refresh rate (producer ≈ composited fps)",
                    ),
                    Check("recompose", "The gallery's recomposition count stays put while the textures animate"),
                    Check(
                        "scale",
                        "FillBounds stretches, Fit letterboxes, Crop fills; " +
                            "the rotated box is clipped and Compose draws on top",
                    ),
                    Check("filter", "None shows hard pixels, High a smooth upscale, on the same frame"),
                    Check(
                        "planar",
                        "Linux: the I420 and YV12 boxes look exactly like the packed ones (colours, orientation)",
                    ),
                    Check(
                        "context",
                        "The scene-context renderer animates; in the tray panel it animates too, " +
                            "with its own context id on macOS/Linux",
                    ),
                ),
            keywords =
                listOf(
                    "textureview",
                    "d3d11",
                    "iosurface",
                    "dmabuf",
                    "eglimage",
                    "zero copy",
                    "skia context",
                    "#338",
                    "#478",
                ),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<TexturesViewModel>()
        val state by vm.state.collectAsState()
        val controllers = rememberTextureControllers()
        val producerMeter = remember { FrameMeter() }
        val compositedMeter = remember { FrameMeter() }
        val contextMeter = remember { FrameMeter() }
        ProducerLoop(vm.bench, controllers, state.animating, producerMeter)

        ProbeLayout(
            capabilities =
                listOf(
                    Capability("External producer", state.producerAvailability),
                    Capability(
                        "Standalone panel",
                        Availability.of(isTaoStandalonePopupAvailable()) { "no standalone popup surface here" },
                    ),
                ),
            controls = {
                SwitchRow("Producers running", state.animating) { vm.onIntent(TexturesIntent.ToggleAnimation) }
                SwitchRow("Tray panel (own surface)", state.trayPanel, enabled = isTaoStandalonePopupAvailable()) {
                    vm.onIntent(TexturesIntent.ToggleTrayPanel)
                }
                SubHeading("Producers")
                if (state.producers.isEmpty()) EmptyState("No external producer on this system.")
                state.producers.forEach { Readout(it.role, "${it.kind} · ${it.syncMode}") }
            },
            observed = {
                FrameRateReadout("producer frames", producerMeter)
                FrameRateReadout("composited draws", compositedMeter)
                FrameRateReadout("context renderer", contextMeter)
                state.contexts[GpuSurface.Window]?.let {
                    Readout("window context", "${it.backend} · skiaContext@${it.skiaContextId}")
                }
                state.contexts[GpuSurface.TrayPanel]?.let {
                    Readout("panel context", "${it.backend} · skiaContext@${it.skiaContextId}")
                }
                state.panelHasOwnContext?.let { Readout("panel owns its context", it.toString()) }
            },
            wide = {
                SubHeading("Scene GPU context (in-process renderer)")
                GpuContextPanel(
                    contextMeter,
                    onContext = { vm.onIntent(TexturesIntent.ContextResolved(GpuSurface.Window, it)) },
                )
                Gallery(vm.bench, controllers, compositedMeter)
            },
        )

        if (isTaoStandalonePopupAvailable()) {
            TaoStandalonePopup(
                visible = state.trayPanel,
                position = WindowPosition.Absolute(40.dp, 40.dp),
                size = DpSize(220.dp, 380.dp),
                focusable = false,
                onOutsideClick = { vm.onIntent(TexturesIntent.ToggleTrayPanel) },
            ) {
                Column(
                    Modifier.background(LabTheme.colors.panel).padding(LabDimens.page),
                    verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
                ) {
                    Text("Tray panel — own surface", style = LabTheme.typography.heading)
                    TextureSpecimen(null, 160.dp, 120.dp) { size ->
                        TextureView(
                            vm.bench.primary?.source,
                            size,
                            controllers.primary,
                            contentScale = ContentScale.FillBounds,
                        )
                    }
                    GpuContextPanel(contextMeter, onContext = {
                        vm.onIntent(TexturesIntent.ContextResolved(GpuSurface.TrayPanel, it))
                    }, size = 160.dp)
                }
            }
        }
    }

    companion object {
        val ID = ProbeId("rendering.textures")
    }
}

/** Its own scope so the recomposition count is the gallery's, not the readouts'. */
@Composable
private fun Gallery(
    bench: TextureBench,
    controllers: TextureControllers,
    compositedMeter: FrameMeter,
) {
    val recompositions = rememberRecompositionCounter()
    Readout("gallery recompositions", recompositions[0].toString())
    TextureGallery(bench, controllers, compositedMeter)
}
