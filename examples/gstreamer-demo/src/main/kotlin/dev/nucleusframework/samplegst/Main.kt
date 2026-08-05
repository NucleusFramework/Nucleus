package dev.nucleusframework.samplegst

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.rememberTextureViewController
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays a video into a `TextureView` with GStreamer: hardware decode, GPU colour
 * conversion, and the frame composited in the Compose scene without ever touching
 * the CPU. Pick a file with the Open button, pass one as the first argument, or
 * set `NUCLEUS_GST_URI`.
 *
 * The controls exist to show the part that matters: the video is not a hole punched
 * through the window, it is a composable. Clip it, fade it, turn it, put Compose on
 * top of it — z-order, transforms and alpha all apply, and none of it costs a copy.
 *
 * Needs the sample's helper library once:
 *   `examples/gstreamer-demo/src/main/native/linux/build.sh`
 */
fun main(args: Array<String>) {
    // No GraalVmInitializer call: nucleusApplication runs it first thing.
    val uri = resolveUri(args.firstOrNull() ?: System.getenv("NUCLEUS_GST_URI"))
    nucleusApplication(backend = NucleusBackend.Tao) {
        NucleusDecoratedWindowTheme(isDark = true) {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                state = rememberWindowState(width = 1080.dp, height = 720.dp),
                title = "Nucleus — GStreamer → TextureView",
            ) {
                val holder = remember(uri) { VideoHolder(uri) }
                DisposableEffect(holder) {
                    onDispose { holder.close() }
                }
                TitleBar {
                    Row(
                        modifier = Modifier.align(Alignment.Start).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (holder.video != null) {
                                            Color(0xFF34D399)
                                        } else {
                                            Color(0xFFF87171)
                                        },
                                    ),
                        )
                        BasicText(
                            text = holder.video?.let { "${it.widthPx}×${it.heightPx}" } ?: "no stream",
                            style = TextStyle(color = Color(0xFFA0A4B0), fontSize = 11.sp),
                        )
                    }
                    BasicText(
                        text = "GStreamer → TextureView",
                        style =
                            TextStyle(
                                color = Color(0xFFE6E6E6),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                    )
                }
                VideoContent(holder)
            }
        }
    }
}

/** How often the frame counter reaches the console. */
private const val LOG_EVERY_FRAMES = 60

/** What the Open dialog offers — the containers GStreamer's plugins demux. */
private val VideoFiles =
    FileKitType.File("mp4", "m4v", "mov", "mkv", "avi", "webm", "ts", "m2ts", "flv", "ogv")

/** `playbin` wants a URI; a plain path is the friendlier thing to type. */
private fun resolveUri(argument: String?): String? {
    if (argument.isNullOrBlank()) return null
    if (argument.contains("://")) return argument
    val file = File(argument)
    return if (file.isFile) file.toURI().toString() else null
}

@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun VideoContent(holder: VideoHolder) {
    val video = holder.video
    val controller = rememberTextureViewController()
    val frames = remember { AtomicInteger() }
    val scope = rememberCoroutineScope()

    var clipped by remember { mutableStateOf(false) }
    var faded by remember { mutableStateOf(false) }
    var turned by remember { mutableStateOf(false) }
    var cropped by remember { mutableStateOf(false) }
    var overlaid by remember { mutableStateOf(true) }
    var muted by remember(video) { mutableStateOf(false) }

    // Display-paced pull: `withFrameNanos` ticks once per composited frame, the GPU
    // work happens on a background dispatcher, and only the draw pass is
    // invalidated — no recomposition per frame.
    LaunchedEffect(video) {
        if (video == null) return@LaunchedEffect
        var total = 0
        while (isActive) {
            withFrameNanos { }
            val arrived = withContext(Dispatchers.Default) { video.pullFrame() }
            if (arrived) {
                controller.markFrameAvailable()
                frames.incrementAndGet()
                // A line now and then, so the sample can be checked from a console
                // as well as with one's eyes.
                if (++total % LOG_EVERY_FRAMES == 0) println("gstreamer-demo: $total frames composited")
            }
        }
    }

    // Increments on every recomposition of this pane — it must not follow the frame
    // rate, which is the whole claim of the feature.
    val recompositions = remember { intArrayOf(0) }
    recompositions[0]++

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF14171C)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = holder.status,
            style = TextStyle(color = Color(0xFFE6E6E6), fontSize = 13.sp),
        )
        if (video != null) FrameRateReadout(frames, recompositions)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Toggle("Open video…", on = false) {
                scope.launch {
                    // The picker is the OS dialog; the load closes the old pipeline
                    // off the UI thread (its GL teardown unbinds a context, which the
                    // render thread must not watch), and stages the new URI for the
                    // next draw pass to bring up — see [VideoHolder.openIfNeeded].
                    val picked = FileKit.openFilePicker(type = VideoFiles)?.path ?: return@launch
                    val uri = resolveUri(picked) ?: return@launch
                    withContext(Dispatchers.IO) { holder.load(uri) }
                }
            }
            Toggle("Rounded clip", clipped) { clipped = !clipped }
            Toggle("50 % alpha", faded) { faded = !faded }
            Toggle("Rotate 8°", turned) { turned = !turned }
            Toggle("Crop", cropped) { cropped = !cropped }
            Toggle("Compose on top", overlaid) { overlaid = !overlaid }
            if (video?.hasAudio == true) {
                Toggle(if (muted) "Unmute" else "Mute", muted) {
                    muted = !muted
                    video.muted = muted
                }
            }
        }

        Box(
            // The pipeline is brought up from a draw pass, which is the one place the
            // window's EGL context is current — composition alone is not, at least
            // not the first one. It happens on the first frame and again whenever a
            // new file is staged by [VideoHolder.load], and the source it produces
            // reaches the composable on the next frame.
            modifier = Modifier.fillMaxSize().drawBehind { holder.openIfNeeded() },
            contentAlignment = Alignment.Center,
        ) {
            // Every modifier here is the ordinary Compose kind: the frame is a
            // texture inside the scene, not a hole punched through the window.
            var videoModifier: Modifier = Modifier.fillMaxSize()
            if (clipped) videoModifier = videoModifier.clip(RoundedCornerShape(28.dp))
            if (turned) videoModifier = videoModifier.rotate(8f)
            if (faded) videoModifier = videoModifier.alpha(0.5f)
            TextureView(
                source = video?.source,
                controller = controller,
                modifier = videoModifier,
                contentScale = if (cropped) ContentScale.Crop else ContentScale.Fit,
            )
            if (overlaid) ComposeOverlay()
        }
    }
}

/** Compose drawn over the video, to show the z-order really is the scene's. */
@Suppress("FunctionNaming")
@Composable
private fun ComposeOverlay() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC15181D))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            BasicText(
                text = "Compose, above the video",
                style = TextStyle(color = Color.White, fontSize = 12.sp),
            )
        }
        // Translucent, so the video shows through it — the frame takes part in
        // blending like any other content.
        Box(
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0x9934D399)),
        )
    }
}

/**
 * Holds the pipeline and the moment it may be created. Opening blocks for the
 * preroll — a fraction of a second — which is why it happens from the draw pass
 * rather than per frame, and why the window's first frame may be a little late.
 *
 * The helper must capture the window's EGL context, current only inside a draw
 * pass, so [openIfNeeded] is the one entry point that actually opens — it is the
 * draw pass that calls it. [load] only stages a new URI (and tears the old
 * pipeline down off the render thread); the next draw pass brings it up.
 */
private class VideoHolder(
    initialUri: String?,
) {
    var video by mutableStateOf<GstVideoTexture?>(null)
        private set

    var status by mutableStateOf(initialStatus(initialUri))
        private set

    private var uri by mutableStateOf(initialUri)

    /** True once the current [uri] has been attempted, success or failure. A state
     * so the draw pass — which may run on the render thread — sees a [load] that
     * ran on a background dispatcher. */
    private var done by mutableStateOf(false)

    /** Call from a draw pass only: it needs the window's EGL context current. */
    fun openIfNeeded() {
        if (done) return
        val target = uri ?: return
        done = true
        val opened = GstVideoTexture.open(target)
        video = opened
        status =
            if (opened == null) {
                "GStreamer could not open the stream — the console carries what the pipeline said"
            } else {
                "Decoded and converted on the GPU, composited with no copy"
            }
    }

    /**
     * Stages [uri] to replace what is playing. Closes the old pipeline first —
     * call from a background dispatcher: GL teardown unbinds a context, which the
     * render thread must not watch happen. The actual open needs the window's EGL
     * context, so it happens on the next draw pass; a file that cannot be opened
     * leaves nothing playing.
     */
    fun load(uri: String) {
        if (!GstVideoTexture.isAvailable) return
        video?.close()
        video = null
        status = "Opening…"
        this.uri = uri
        done = false
    }

    fun close() {
        video?.close()
        video = null
    }

    private companion object {
        fun initialStatus(uri: String?): String =
            when {
                !GstVideoTexture.isAvailable ->
                    "Helper library missing — run examples/gstreamer-demo/src/main/native/linux/build.sh"

                uri == null ->
                    "Pick a video with Open video…, or pass one as the first argument, or set NUCLEUS_GST_URI"

                else -> "Opening…"
            }
    }
}

/**
 * Frame rate and recomposition count, sampled once a second in a composable of its
 * own so the state read stays out of the video pane — otherwise its recomposition
 * would be the very thing this sample claims not to do.
 */
@Suppress("FunctionNaming")
@Composable
private fun FrameRateReadout(
    frames: AtomicInteger,
    recompositions: IntArray,
) {
    var rate by remember { mutableStateOf(0) }
    LaunchedEffect(frames) {
        while (isActive) {
            delay(1_000)
            rate = frames.getAndSet(0)
        }
    }
    BasicText(
        text = "$rate frames/s · ${recompositions[0]} recompositions of the video pane",
        style = TextStyle(color = Color(0xFFA0A4B0), fontSize = 11.sp),
    )
}

/** A checkbox-ish button, kept plain so the sample needs no design system. */
@Suppress("FunctionNaming")
@Composable
private fun Toggle(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (on) Color(0xFF2F6F4F) else Color(0xFF2A3340))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = Color(0xFFE6E6E6), fontSize = 12.sp),
        )
    }
}
