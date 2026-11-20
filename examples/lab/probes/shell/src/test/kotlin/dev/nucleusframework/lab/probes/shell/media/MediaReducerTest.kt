package dev.nucleusframework.lab.probes.shell.media

import dev.nucleusframework.media.control.MediaControlEvent
import dev.nucleusframework.media.control.MediaPlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaReducerTest {
    private fun reduce(
        state: MediaState,
        vararg events: MediaEvent,
    ) = events.fold(state) { s, e -> MediaReducer.reduce(s, e) }

    @Test
    fun `next wraps around the playlist and rewinds`() {
        val last = MediaState(trackIndex = Playlist.lastIndex, positionMs = 10_000)
        val next = reduce(last, MediaEvent.Applied(MediaControlEvent.Next))
        assertEquals(0, next.trackIndex)
        assertEquals(0, next.positionMs)
    }

    @Test
    fun `previous restarts the track past three seconds, skips back before`() {
        val late = MediaState(trackIndex = 1, positionMs = 5_000)
        assertEquals(1, reduce(late, MediaEvent.Applied(MediaControlEvent.Previous)).trackIndex)
        assertEquals(0, reduce(late, MediaEvent.Applied(MediaControlEvent.Previous)).positionMs)

        val early = MediaState(trackIndex = 1, positionMs = 1_000)
        assertEquals(0, reduce(early, MediaEvent.Applied(MediaControlEvent.Previous)).trackIndex)
    }

    @Test
    fun `seeks and volume are clamped`() {
        val state = MediaState(positionMs = 1_000)
        assertEquals(0, reduce(state, MediaEvent.Applied(MediaControlEvent.SeekBy(-60_000))).positionMs)
        assertEquals(
            Playlist[0].durationMs,
            reduce(state, MediaEvent.Applied(MediaControlEvent.SetPosition(Long.MAX_VALUE))).positionMs,
        )
        assertEquals(1.0, reduce(state, MediaEvent.Applied(MediaControlEvent.SetVolume(4.0))).volume)
    }

    @Test
    fun `ticks only advance a playing track and roll over at its end`() {
        val paused = MediaState(status = MediaPlaybackStatus.PAUSED, positionMs = 2_000)
        assertEquals(2_000, reduce(paused, MediaEvent.Ticked(1_000)).positionMs)

        val ending = MediaState(status = MediaPlaybackStatus.PLAYING, positionMs = Playlist[0].durationMs - 500)
        val rolled = reduce(ending, MediaEvent.Ticked(1_000))
        assertEquals(1, rolled.trackIndex)
        assertEquals(0, rolled.positionMs)
    }

    @Test
    fun `toggle flips between playing and paused`() {
        val playing = reduce(MediaState(), MediaEvent.Applied(MediaControlEvent.Toggle))
        assertEquals(MediaPlaybackStatus.PLAYING, playing.status)
        assertEquals(MediaPlaybackStatus.PAUSED, reduce(playing, MediaEvent.Applied(MediaControlEvent.Toggle)).status)
    }
}
