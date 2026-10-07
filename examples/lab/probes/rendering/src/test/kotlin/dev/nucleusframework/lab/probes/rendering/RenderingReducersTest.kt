package dev.nucleusframework.lab.probes.rendering

import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.probes.rendering.partialredraw.PartialRedrawEvent
import dev.nucleusframework.lab.probes.rendering.partialredraw.PartialRedrawReducer
import dev.nucleusframework.lab.probes.rendering.partialredraw.PartialRedrawState
import dev.nucleusframework.lab.probes.rendering.showcase.ShowcaseEvent
import dev.nucleusframework.lab.probes.rendering.showcase.ShowcaseReducer
import dev.nucleusframework.lab.probes.rendering.showcase.ShowcaseState
import dev.nucleusframework.lab.probes.rendering.swiftui.SwiftUiEvent
import dev.nucleusframework.lab.probes.rendering.swiftui.SwiftUiReducer
import dev.nucleusframework.lab.probes.rendering.swiftui.SwiftUiState
import dev.nucleusframework.lab.probes.rendering.textures.GpuContextInfo
import dev.nucleusframework.lab.probes.rendering.textures.GpuSurface
import dev.nucleusframework.lab.probes.rendering.textures.TexturesEvent
import dev.nucleusframework.lab.probes.rendering.textures.TexturesReducer
import dev.nucleusframework.lab.probes.rendering.textures.TexturesState
import dev.nucleusframework.lab.probes.rendering.video.Playback
import dev.nucleusframework.lab.probes.rendering.video.VideoEvent
import dev.nucleusframework.lab.probes.rendering.video.VideoReducer
import dev.nucleusframework.lab.probes.rendering.video.VideoState
import dev.nucleusframework.lab.probes.rendering.video.VideoTransform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RenderingReducersTest {
    @Test
    fun `showcase toggles one blob and ignores an out-of-range index`() {
        val toggled = ShowcaseReducer.reduce(ShowcaseState(), ShowcaseEvent.BlobToggled(2))
        assertEquals(listOf(true, true, false, true), toggled.blobs)
        assertSame(toggled, ShowcaseReducer.reduce(toggled, ShowcaseEvent.BlobToggled(9)))
    }

    @Test
    fun `partial redraw interval is clamped to the slider range`() {
        val low = PartialRedrawReducer.reduce(PartialRedrawState(), PartialRedrawEvent.IntervalChanged(1))
        val high = PartialRedrawReducer.reduce(PartialRedrawState(), PartialRedrawEvent.IntervalChanged(60_000))
        assertEquals(PartialRedrawReducer.MIN_INTERVAL, low.intervalMillis)
        assertEquals(PartialRedrawReducer.MAX_INTERVAL, high.intervalMillis)
    }

    @Test
    fun `swiftui counts created and released handles`() {
        var state = SwiftUiState()
        state = SwiftUiReducer.reduce(state, SwiftUiEvent.ViewCreated(0x10))
        assertEquals(1, state.live)
        state = SwiftUiReducer.reduce(state, SwiftUiEvent.ViewReleased)
        state = SwiftUiReducer.reduce(state, SwiftUiEvent.ViewCreated(0x20))
        assertEquals(2, state.created)
        assertEquals(1, state.live)
        assertEquals(0x20, state.viewAddress)
    }

    @Test
    fun `swiftui hue stays in unit range`() {
        assertEquals(1f, SwiftUiReducer.reduce(SwiftUiState(), SwiftUiEvent.HueChanged(4f)).hue)
    }

    @Test
    fun `textures report no producer as unavailable`() {
        val state = TexturesReducer.reduce(TexturesState(), TexturesEvent.ProducersCreated(emptyList()))
        assertIs<Availability.Unavailable>(state.producerAvailability)
    }

    @Test
    fun `tray panel context ownership needs both surfaces`() {
        var state =
            TexturesReducer.reduce(
                TexturesState(),
                TexturesEvent.ContextResolved(GpuSurface.Window, GpuContextInfo("METAL", "a")),
            )
        assertNull(state.panelHasOwnContext)
        state =
            TexturesReducer.reduce(
                state,
                TexturesEvent.ContextResolved(GpuSurface.TrayPanel, GpuContextInfo("METAL", "b")),
            )
        assertEquals(true, state.panelHasOwnContext)
    }

    @Test
    fun `video playback goes opening, playing, idle`() {
        var state = VideoReducer.reduce(VideoState(), VideoEvent.OpenRequested("file:/a.mp4"))
        assertIs<Playback.Opening>(state.playback)
        state =
            VideoReducer.reduce(state, VideoEvent.Opened("file:/a.mp4", 1920, 1080, hasAudio = true, openMillis = 40))
        val playing = assertIs<Playback.Playing>(state.playback)
        assertEquals(1920, playing.widthPx)
        state = VideoReducer.reduce(state, VideoEvent.Stopped)
        assertEquals(Playback.Idle, state.playback)
    }

    @Test
    fun `video transforms toggle independently`() {
        var state = VideoReducer.reduce(VideoState(), VideoEvent.Toggled(VideoTransform.Clip))
        state = VideoReducer.reduce(state, VideoEvent.Toggled(VideoTransform.Overlay))
        assertTrue(VideoTransform.Clip in state.transforms)
        assertTrue(VideoTransform.Overlay !in state.transforms)
    }
}
