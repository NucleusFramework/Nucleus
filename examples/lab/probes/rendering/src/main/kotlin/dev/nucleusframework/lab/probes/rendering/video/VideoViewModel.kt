package dev.nucleusframework.lab.probes.rendering.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.time.timedMillis
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Owns the decode chain, so leaving the probe keeps playing state and coming back re-imports
 * the same source. A backend that opens in the draw pass (GStreamer) gets its target staged
 * here and opened by [openInDrawPass].
 */
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class VideoViewModel(
    private val backend: VideoBackend,
    timeline: Timeline,
) : MviViewModel<VideoState, VideoIntent, VideoEvent, Nothing>(VideoState(), VideoReducer, timeline, VideoProbe.ID) {
    private val currentStream = MutableStateFlow<VideoStream?>(null)

    /** The playing stream; the composition imports its source and runs the pull loop. */
    val stream: StateFlow<VideoStream?> = currentStream.asStateFlow()

    // Snapshot state on purpose: reading it inside the draw pass is what invalidates that pass
    // when a target is staged, so the open happens on the very next frame.
    private var stagedForDrawPass by mutableStateOf<String?>(null)

    init {
        dispatch(VideoEvent.BackendRead(backend.name, backend.pipeline, backend.availability))
        (
            System.getProperty("lab.video.url") ?: System.getenv("NUCLEUS_GST_URI") ?: System.getenv("NUCLEUS_MF_URL")
                ?: System.getenv("NUCLEUS_AVF_URL")
        )?.let { launch { open(it) } }
    }

    override suspend fun handle(intent: VideoIntent) {
        when (intent) {
            is VideoIntent.EditInput -> reduceSilently(VideoEvent.InputEdited(intent.text))
            VideoIntent.OpenInput -> open(state.value.input)
            VideoIntent.PickFile -> {
                val picked = FileKit.openFilePicker(type = FileKitType.File(backend.fileExtensions)) ?: return
                open(picked.path)
            }
            is VideoIntent.Toggle -> dispatch(VideoEvent.Toggled(intent.transform))
            VideoIntent.ToggleMute -> {
                dispatch(VideoEvent.MuteToggled)
                currentStream.value?.setMuted(state.value.muted)
            }
            VideoIntent.Stop -> stop()
        }
    }

    private suspend fun open(input: String) {
        if (!backend.availability.isAvailable) return
        val target = backend.resolve(input.trim())
        if (target == null) {
            dispatch(VideoEvent.OpenFailed(input, "not a file or URL"), Severity.Error)
            return
        }
        closeCurrent()
        dispatch(VideoEvent.OpenRequested(target))
        if (backend.opensInDrawPass) {
            stagedForDrawPass = target
        } else {
            val (opened, millis) = timedMillis { io { backend.open(target) } }
            onOpened(target, opened, millis)
        }
    }

    /** Called from the video box's draw pass, where GStreamer can capture the window's EGL context. */
    fun openInDrawPass() {
        val target = stagedForDrawPass ?: return
        stagedForDrawPass = null
        val (opened, millis) = timedMillis { backend.open(target) }
        onOpened(target, opened, millis)
    }

    private fun onOpened(
        target: String,
        opened: VideoStream?,
        openMillis: Long,
    ) {
        if (opened == null) {
            dispatch(
                VideoEvent.OpenFailed(
                    target,
                    "${backend.name} could not open it — stderr carries the pipeline's reason",
                ),
                Severity.Error,
            )
            return
        }
        opened.setMuted(state.value.muted)
        currentStream.value = opened
        dispatch(VideoEvent.Opened(target, opened.widthPx, opened.heightPx, opened.hasAudio, openMillis))
    }

    private suspend fun stop() {
        stagedForDrawPass = null
        closeCurrent()
        dispatch(VideoEvent.Stopped)
    }

    override fun onCleared() {
        currentStream.value?.close()
        currentStream.value = null
    }

    private suspend fun closeCurrent() {
        io { currentStream.getAndUpdate { null }?.close() }
    }
}
