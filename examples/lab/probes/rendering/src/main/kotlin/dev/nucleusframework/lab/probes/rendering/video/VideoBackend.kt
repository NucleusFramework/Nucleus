package dev.nucleusframework.lab.probes.rendering.video

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.window.tao.TextureViewSource
import dev.nucleusframework.window.tao.nucleusD3D11SharedTextureSource
import dev.nucleusframework.window.tao.nucleusEglImageTextureSource
import dev.nucleusframework.window.tao.nucleusIOSurfaceTextureSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.io.File

/** One platform decoder feeding a `TextureView`: the port the video probe talks to. */
interface VideoBackend {
    val name: String
    val pipeline: String
    val availability: Availability

    /** GStreamer captures the window's EGL context: it may only open from a draw pass. */
    val opensInDrawPass: Boolean
    val fileExtensions: List<String>

    /** A plain path becomes what the backend takes (a `file:` URI for GStreamer); `null` if unusable. */
    fun resolve(input: String): String?

    /** Opens [target] and decodes its first frame; `null` when undecodable. Blocks. */
    fun open(target: String): VideoStream?
}

/** An open decode chain. Its [source] is stable for the whole playback: one import. */
class VideoStream(
    val widthPx: Int,
    val heightPx: Int,
    val source: TextureViewSource,
    val hasAudio: Boolean,
    private val pull: () -> Int,
    private val mute: (Boolean) -> Unit,
    private val closeNative: () -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    // A decode chain outliving an abrupt exit touches GPU objects the toolkit tore down.
    private val shutdownHook = Thread(::close, "lab-video-shutdown").also { Runtime.getRuntime().addShutdownHook(it) }

    /** True when a new frame reached the texture: signal the controller then. */
    fun pullFrame(): Boolean = synchronized(lock) { !closed && pull() == 1 }

    fun setMuted(muted: Boolean) = synchronized(lock) { if (!closed && hasAudio) mute(muted) }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            closeNative()
        }
        // Not from the hook itself: removing a running hook throws.
        if (Thread.currentThread() !==
            shutdownHook
        ) {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }
}

@ContributesBinding(AppScope::class)
@Inject
class PlatformVideoBackend : VideoBackend by select()

private fun select(): VideoBackend =
    when (Platform.Current) {
        Platform.Linux -> GstBackend
        Platform.Windows -> MfBackend
        Platform.MacOS -> AvfBackend
        Platform.Unknown -> GstBackend
    }

private fun helperMissing(script: String) =
    "helper library missing — run :examples:lab:probes:rendering:buildRenderingNatives ($script)"

private object GstBackend : VideoBackend {
    override val name = "GStreamer"
    override val pipeline = "uridecodebin → glcolorconvert → EGLImage (one GPU copy, no CPU copy)"
    override val availability get() =
        when {
            Platform.Current != Platform.Linux -> Availability.Unavailable("GStreamer backend is Linux-only")
            !NativeGstVideoBridge.isLoaded ->
                Availability.Unavailable(
                    helperMissing("linux/build-gst-video.sh, needs libgstreamer1.0-dev"),
                )
            else -> Availability.Available
        }
    override val opensInDrawPass = true
    override val fileExtensions = listOf("mp4", "m4v", "mov", "mkv", "avi", "webm", "ts", "m2ts", "flv", "ogv")

    override fun resolve(input: String): String? =
        if ("://" in
            input
        ) {
            input
        } else {
            File(input).takeIf { it.isFile }?.toURI()?.toString()
        }

    override fun open(target: String): VideoStream? {
        val handle = NativeGstVideoBridge.nativeOpen(target).takeIf { it != 0L } ?: return null
        val width = NativeGstVideoBridge.nativeWidth(handle)
        val height = NativeGstVideoBridge.nativeHeight(handle)
        val image = NativeGstVideoBridge.nativeEglImage(handle)
        if (width < 1 || height < 1 || image == 0L) return null.also { NativeGstVideoBridge.nativeClose(handle) }
        return VideoStream(
            width,
            height,
            nucleusEglImageTextureSource(image, width, height),
            NativeGstVideoBridge.nativeHasAudio(handle),
            pull = { NativeGstVideoBridge.nativePullFrame(handle) },
            mute = { NativeGstVideoBridge.nativeSetMuted(handle, it) },
            closeNative = { NativeGstVideoBridge.nativeClose(handle) },
        )
    }
}

private object MfBackend : VideoBackend {
    override val name = "Media Foundation"
    override val pipeline = "IMFSourceReader (DXVA) → ID3D11VideoProcessor → shared D3D11 texture (zero copy)"
    override val availability get() =
        when {
            Platform.Current != Platform.Windows -> Availability.Unavailable("Media Foundation backend is Windows-only")
            !NativeMfVideoBridge.isLoaded ->
                Availability.Unavailable(
                    helperMissing("windows\\build-mf-video.bat, needs VS Build Tools"),
                )
            else -> Availability.Available
        }
    override val opensInDrawPass = false
    override val fileExtensions = listOf("mp4", "m4v", "mov", "mkv", "avi", "wmv", "webm")

    override fun resolve(input: String): String? =
        if ("://" in
            input
        ) {
            input
        } else {
            File(input).takeIf { it.isFile }?.absolutePath
        }

    override fun open(target: String): VideoStream? {
        val handle = NativeMfVideoBridge.nativeOpen(target).takeIf { it != 0L } ?: return null
        val width = NativeMfVideoBridge.nativeWidth(handle)
        val height = NativeMfVideoBridge.nativeHeight(handle)
        val shared = NativeMfVideoBridge.nativeSharedHandle(handle)
        if (width < 1 || height < 1 || shared == 0L) return null.also { NativeMfVideoBridge.nativeClose(handle) }
        return VideoStream(
            width,
            height,
            nucleusD3D11SharedTextureSource(shared, width, height),
            NativeMfVideoBridge.nativeHasAudio(handle),
            pull = { NativeMfVideoBridge.nativePullFrame(handle) },
            mute = { NativeMfVideoBridge.nativeSetMuted(handle, it) },
            closeNative = { NativeMfVideoBridge.nativeClose(handle) },
        )
    }
}

private object AvfBackend : VideoBackend {
    override val name = "AVFoundation"
    override val pipeline = "AVAssetReader (VideoToolbox) → Metal Y'CbCr→BGRA pass → IOSurface (no CPU copy)"
    override val availability get() =
        when {
            Platform.Current != Platform.MacOS -> Availability.Unavailable("AVFoundation backend is macOS-only")
            !NativeAvfVideoBridge.isLoaded -> Availability.Unavailable(helperMissing("macos/build-avf-video.sh"))
            else -> Availability.Available
        }
    override val opensInDrawPass = false
    override val fileExtensions = listOf("mp4", "m4v", "mov", "mkv", "avi", "m2ts", "ts", "webm")

    override fun resolve(input: String): String? =
        if ("://" in
            input
        ) {
            input
        } else {
            File(input).takeIf { it.isFile }?.absolutePath
        }

    override fun open(target: String): VideoStream? {
        val handle = NativeAvfVideoBridge.nativeOpen(target).takeIf { it != 0L } ?: return null
        val width = NativeAvfVideoBridge.nativeWidth(handle)
        val height = NativeAvfVideoBridge.nativeHeight(handle)
        val surface = NativeAvfVideoBridge.nativeIoSurface(handle)
        if (width < 1 || height < 1 || surface == 0L) return null.also { NativeAvfVideoBridge.nativeClose(handle) }
        return VideoStream(
            width,
            height,
            nucleusIOSurfaceTextureSource(surface, width, height),
            NativeAvfVideoBridge.nativeAudioEnabled(handle),
            pull = { NativeAvfVideoBridge.nativePullFrame(handle) },
            mute = { NativeAvfVideoBridge.nativeSetMuted(handle, it) },
            closeNative = { NativeAvfVideoBridge.nativeClose(handle) },
        )
    }
}
