package dev.nucleusframework.lab.probes.rendering.video

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Reducer

sealed interface Playback {
    data object Idle : Playback

    data class Opening(
        val target: String,
    ) : Playback

    data class Playing(
        val target: String,
        val widthPx: Int,
        val heightPx: Int,
        val hasAudio: Boolean,
        val openMillis: Long,
    ) : Playback

    data class Failed(
        val target: String,
        val reason: String,
    ) : Playback
}

/** The composable modifiers applied to the video: proof it is a texture in the scene, not a hole. */
enum class VideoTransform(
    val label: String,
) {
    Clip("Rounded clip"),
    Fade("50 % alpha"),
    Rotate("Rotate 8°"),
    Crop("Crop"),
    Overlay("Compose on top"),
}

@Immutable
data class VideoState(
    val backend: String = "",
    val pipeline: String = "",
    val availability: Availability = Availability.Unknown,
    val playback: Playback = Playback.Idle,
    val transforms: Set<VideoTransform> = setOf(VideoTransform.Overlay),
    val muted: Boolean = false,
    val input: String = "",
)

sealed interface VideoIntent {
    data class EditInput(
        val text: String,
    ) : VideoIntent

    data object OpenInput : VideoIntent

    data object PickFile : VideoIntent

    data class Toggle(
        val transform: VideoTransform,
    ) : VideoIntent

    data object ToggleMute : VideoIntent

    data object Stop : VideoIntent
}

sealed interface VideoEvent {
    data class BackendRead(
        val name: String,
        val pipeline: String,
        val availability: Availability,
    ) : VideoEvent

    data class InputEdited(
        val text: String,
    ) : VideoEvent

    data class OpenRequested(
        val target: String,
    ) : VideoEvent

    data class Opened(
        val target: String,
        val widthPx: Int,
        val heightPx: Int,
        val hasAudio: Boolean,
        val openMillis: Long,
    ) : VideoEvent

    data class OpenFailed(
        val target: String,
        val reason: String,
    ) : VideoEvent

    data object Stopped : VideoEvent

    data class Toggled(
        val transform: VideoTransform,
    ) : VideoEvent

    data object MuteToggled : VideoEvent
}

object VideoReducer : Reducer<VideoState, VideoEvent> {
    override fun reduce(
        state: VideoState,
        event: VideoEvent,
    ): VideoState =
        when (event) {
            is VideoEvent.BackendRead ->
                state.copy(
                    backend = event.name,
                    pipeline = event.pipeline,
                    availability = event.availability,
                )
            is VideoEvent.InputEdited -> state.copy(input = event.text)
            is VideoEvent.OpenRequested -> state.copy(playback = Playback.Opening(event.target))
            is VideoEvent.Opened ->
                state.copy(
                    playback =
                        Playback.Playing(
                            event.target,
                            event.widthPx,
                            event.heightPx,
                            event.hasAudio,
                            event.openMillis,
                        ),
                )
            is VideoEvent.OpenFailed -> state.copy(playback = Playback.Failed(event.target, event.reason))
            VideoEvent.Stopped -> state.copy(playback = Playback.Idle)
            is VideoEvent.Toggled ->
                state.copy(
                    transforms =
                        if (event.transform in
                            state.transforms
                        ) {
                            state.transforms - event.transform
                        } else {
                            state.transforms + event.transform
                        },
                )
            VideoEvent.MuteToggled -> state.copy(muted = !state.muted)
        }
}
