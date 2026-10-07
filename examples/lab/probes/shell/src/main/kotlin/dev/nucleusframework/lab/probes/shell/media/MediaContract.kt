package dev.nucleusframework.lab.probes.shell.media

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.plusDelivery
import dev.nucleusframework.media.control.MediaControlEvent
import dev.nucleusframework.media.control.MediaPlaybackStatus

@Immutable
data class Track(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/** A tiny fake player: the OS controls drive it, it pushes its state back to the OS. */
val Playlist: List<Track> =
    listOf(
        Track("Nucleus Overture", "The Lab Ensemble", "Probes, Vol. 1", 185_000),
        Track("Timeline in D", "The Lab Ensemble", "Probes, Vol. 1", 242_000),
        Track("Ünïcödé — Título", "Ïntérnatiönal Ärtist", "Ξ Encodings ✓", 63_000),
    )

@Immutable
data class MediaState(
    val availability: Availability = Availability.Unknown,
    val backend: String = "",
    val attached: Boolean = false,
    val trackIndex: Int = 0,
    val status: MediaPlaybackStatus = MediaPlaybackStatus.STOPPED,
    val positionMs: Long = 0,
    val volume: Double = 1.0,
    val withCover: Boolean = true,
    val commands: List<Delivery> = emptyList(),
) {
    val track: Track get() = Playlist[trackIndex]
}

sealed interface MediaIntent {
    data object Attach : MediaIntent

    data object Detach : MediaIntent

    /** In-app transport buttons go through the same path as OS commands. */
    data class Command(
        val event: MediaControlEvent,
    ) : MediaIntent

    data class SetCover(
        val enabled: Boolean,
    ) : MediaIntent
}

sealed interface MediaEvent {
    data class Ready(
        val availability: Availability,
        val backend: String,
    ) : MediaEvent

    data class AttachedChanged(
        val attached: Boolean,
    ) : MediaEvent

    /** An OS command, as received. */
    data class Received(
        val delivery: Delivery,
    ) : MediaEvent

    data class Applied(
        val event: MediaControlEvent,
    ) : MediaEvent

    data class CoverChanged(
        val enabled: Boolean,
    ) : MediaEvent

    data class Ticked(
        val elapsedMs: Long,
    ) : MediaEvent
}

object MediaReducer : Reducer<MediaState, MediaEvent> {
    override fun reduce(
        state: MediaState,
        event: MediaEvent,
    ): MediaState =
        when (event) {
            is MediaEvent.Ready -> state.copy(availability = event.availability, backend = event.backend)
            is MediaEvent.AttachedChanged -> state.copy(attached = event.attached)
            is MediaEvent.Received -> state.copy(commands = state.commands.plusDelivery(event.delivery))
            is MediaEvent.Applied -> state.apply(event.event)
            is MediaEvent.CoverChanged -> state.copy(withCover = event.enabled)
            is MediaEvent.Ticked ->
                if (state.status != MediaPlaybackStatus.PLAYING) {
                    state
                } else if (state.positionMs + event.elapsedMs >= state.track.durationMs) {
                    state.skip(+1)
                } else {
                    state.copy(positionMs = state.positionMs + event.elapsedMs)
                }
        }

    /** The player's response to a transport command. */
    fun MediaState.apply(command: MediaControlEvent): MediaState =
        when (command) {
            MediaControlEvent.Play -> copy(status = MediaPlaybackStatus.PLAYING)
            MediaControlEvent.Pause -> copy(status = MediaPlaybackStatus.PAUSED)
            MediaControlEvent.Toggle ->
                copy(
                    status =
                        if (status == MediaPlaybackStatus.PLAYING) {
                            MediaPlaybackStatus.PAUSED
                        } else {
                            MediaPlaybackStatus.PLAYING
                        },
                )
            MediaControlEvent.Stop -> copy(status = MediaPlaybackStatus.STOPPED, positionMs = 0)
            MediaControlEvent.Next -> skip(+1)
            MediaControlEvent.Previous -> if (positionMs > RESTART_THRESHOLD_MS) copy(positionMs = 0) else skip(-1)
            is MediaControlEvent.SeekBy ->
                copy(
                    positionMs = (positionMs + command.offsetMs).coerceIn(0, track.durationMs),
                )
            is MediaControlEvent.SetPosition -> copy(positionMs = command.positionMs.coerceIn(0, track.durationMs))
            is MediaControlEvent.SetVolume -> copy(volume = command.volume.coerceIn(0.0, 1.0))
            is MediaControlEvent.OpenUri, MediaControlEvent.Raise, MediaControlEvent.Quit -> this
        }

    private fun MediaState.skip(delta: Int): MediaState =
        copy(trackIndex = Math.floorMod(trackIndex + delta, Playlist.size), positionMs = 0)

    private const val RESTART_THRESHOLD_MS = 3_000L
}
