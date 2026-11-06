package dev.nucleusframework.lab.probes.shell.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.mvi.toDelivery
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.media.control.MediaControlEvent
import dev.nucleusframework.media.control.MediaMetadata
import dev.nucleusframework.media.control.MediaPlaybackState
import dev.nucleusframework.media.control.MediaPlaybackStatus
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class MediaViewModel(
    private val gateway: MediaGateway,
    timeline: Timeline,
) : MviViewModel<MediaState, MediaIntent, MediaEvent, Nothing>(MediaState(), MediaReducer, timeline, MediaProbe.ID) {
    private var subscription: Job? = null
    private var ticker: Job? = null

    /** What the OS was last told, so only real changes are pushed. */
    private var pushedTrack: Int? = null
    private var pushedVolume: Double? = null
    private var pushedCover: Boolean? = null

    init {
        dispatch(MediaEvent.Ready(gateway.availability(), gateway.backendName()))
    }

    override suspend fun handle(intent: MediaIntent) {
        when (intent) {
            MediaIntent.Attach -> attach()
            MediaIntent.Detach -> {
                subscription?.cancel()
                ticker?.cancel()
                dispatch(MediaEvent.AttachedChanged(false))
            }
            is MediaIntent.Command -> apply(intent.event)
            is MediaIntent.SetCover -> {
                dispatch(MediaEvent.CoverChanged(intent.enabled))
                push()
            }
        }
    }

    private fun attach() {
        if (subscription?.isActive == true) return
        gateway.configure()
        pushedTrack = null
        subscription =
            viewModelScope.launch {
                gateway.events().collect { stamped ->
                    val delivery = stamped.map { it.toString() }.toDelivery()
                    dispatch(stamped.map { MediaEvent.Received(delivery) })
                    apply(stamped.value)
                }
            }
        ticker =
            viewModelScope.launch {
                var seconds = 0
                while (isActive) {
                    delay(1_000)
                    val trackBefore = state.value.trackIndex
                    reduceSilently(MediaEvent.Ticked(1_000))
                    // The OS extrapolates the position; resync it every few seconds and on track change.
                    if (state.value.status == MediaPlaybackStatus.PLAYING &&
                        (++seconds % RESYNC_SECONDS == 0 || trackBefore != state.value.trackIndex)
                    ) {
                        push()
                    }
                }
            }
        dispatch(MediaEvent.AttachedChanged(true))
        push()
    }

    private fun apply(command: MediaControlEvent) {
        dispatch(MediaEvent.Applied(command))
        push()
    }

    private fun push() {
        if (subscription?.isActive != true) return
        val s = state.value
        if (pushedTrack != s.trackIndex || pushedCover != s.withCover) {
            gateway.setMetadata(
                MediaMetadata(
                    title = s.track.title,
                    artist = s.track.artist,
                    album = s.track.album,
                    coverUrl = if (s.withCover) gateway.coverUri() else null,
                    duration = s.track.durationMs,
                ),
            )
            pushedTrack = s.trackIndex
            pushedCover = s.withCover
        }
        gateway.setPlaybackState(MediaPlaybackState(s.status, s.positionMs))
        if (pushedVolume != s.volume) {
            gateway.setVolume(s.volume)
            pushedVolume = s.volume
        }
    }

    private companion object {
        const val RESYNC_SECONDS = 5
    }
}
