package dev.nucleusframework.lab.probes.rendering.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.probes.rendering.common.FrameMeter
import dev.nucleusframework.lab.probes.rendering.common.FrameRateReadout
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class VideoProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Platform video",
            domain = Domain.Rendering,
            summary =
                "Does hardware-decoded video reach a TextureView with no CPU copy and " +
                    "behave like any composable?",
            modules = listOf("decorated-window-tao"),
            checks =
                listOf(
                    Check(
                        "play",
                        "A picked file plays at its own rate (fps ≈ the file's), audio in sync when it has a track",
                    ),
                    Check("recompose", "The video pane's recomposition count stays put while frames arrive"),
                    Check("transforms", "Clip, 50 % alpha, rotation and crop all apply to the moving picture"),
                    Check(
                        "overlay",
                        "Compose on top stays above the video; the translucent dot shows the video through it",
                    ),
                    Check("reopen", "Opening a second file replaces the first without a frozen or black frame"),
                    Check("mute", "Mute silences the audio without stalling the picture"),
                ),
            keywords =
                listOf(
                    "gstreamer",
                    "media foundation",
                    "avfoundation",
                    "dxva",
                    "videotoolbox",
                    "textureview",
                    "zero copy",
                ),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<VideoViewModel>()
        val state by vm.state.collectAsState()
        val stream by vm.stream.collectAsState()
        val meter = remember { FrameMeter() }

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(state.backend.ifEmpty { "Video backend" }, state.availability, detail = state.pipeline),
                ),
            controls = {
                TextFieldRow("File path or URL", state.input) { vm.onIntent(VideoIntent.EditInput(it)) }
                Actions {
                    PrimaryAction(
                        "Open",
                        enabled = state.availability.isAvailable,
                    ) { vm.onIntent(VideoIntent.OpenInput) }
                    SecondaryAction(
                        "Pick a file…",
                        enabled = state.availability.isAvailable,
                    ) { vm.onIntent(VideoIntent.PickFile) }
                    SecondaryAction(
                        "Stop",
                        enabled = state.playback !is Playback.Idle,
                    ) { vm.onIntent(VideoIntent.Stop) }
                }
                SubHeading("Transforms")
                Hint("The video is a composable: every modifier applies.")
                VideoTransform.entries.forEach { transform ->
                    SwitchRow(
                        transform.label,
                        transform in state.transforms,
                    ) { vm.onIntent(VideoIntent.Toggle(transform)) }
                }
                SwitchRow("Mute", state.muted, enabled = (state.playback as? Playback.Playing)?.hasAudio == true) {
                    vm.onIntent(VideoIntent.ToggleMute)
                }
            },
            observed = {
                Readout("pipeline", state.pipeline)
                PlaybackReadouts(state.playback)
                FrameRateReadout("frames composited", meter, budgetMillis = 100.0)
            },
            wide = { VideoPane(stream, state.transforms, meter, onDrawPass = vm::openInDrawPass) },
        )
    }

    companion object {
        val ID = ProbeId("rendering.video")
    }
}

@Composable
private fun PlaybackReadouts(playback: Playback) {
    when (playback) {
        Playback.Idle -> Readout("playback", "idle — pick a file or pass -Dlab.video.url", tone = Tone.Muted)
        is Playback.Opening -> Readout("playback", "opening ${playback.target}")
        is Playback.Playing -> {
            Readout("playing", playback.target, tone = Tone.Ok)
            Readout("size", "${playback.widthPx}×${playback.heightPx} px")
            Readout("audio", if (playback.hasAudio) "track present" else "no audio track")
            Readout("first frame after", "${playback.openMillis} ms")
        }
        is Playback.Failed -> Readout("failed", "${playback.target}: ${playback.reason}", tone = Tone.Error)
    }
}
