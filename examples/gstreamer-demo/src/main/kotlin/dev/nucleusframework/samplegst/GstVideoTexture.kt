package dev.nucleusframework.samplegst

import dev.nucleusframework.window.tao.TextureViewSource
import dev.nucleusframework.window.tao.nucleusEglImageTextureSource

/**
 * A GStreamer pipeline feeding one [TextureViewSource]: hardware decode, GPU
 * colour conversion, and the frame handed over as an `EGLImage` the compositor
 * samples in place.
 *
 * The decoder's output is YUV, which Skia cannot sample, so `glcolorconvert` turns
 * it into RGBA **on the GPU** — no CPU copy, and the same division of labour
 * Flutter's Linux embedder settled on (its texture API takes RGBA and leaves the
 * conversion to the plugin). What the helper adds is one GPU copy per frame into a
 * texture of its own, so the source stays the same object for the whole playback:
 * one import, and not a single recomposition per frame.
 *
 * [open] **must be called from inside a Compose render pass** — a `remember {}` in
 * the window's content — because the helper captures the window's EGL context there
 * and shares everything from it.
 *
 * [pullFrame] is meant for a producer loop on a background dispatcher, exactly like
 * the bundled test producers: it binds a context of its own, so the render thread
 * is never disturbed. Serialised here, since one EGL context cannot be current on
 * two threads at once.
 */
class GstVideoTexture private constructor(
    private val handle: Long,
    val widthPx: Int,
    val heightPx: Int,
    val source: TextureViewSource,
    val hasAudio: Boolean,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    /**
     * A pipeline outlives an abrupt exit, and its GL thread then touches a context
     * the toolkit has already torn down — which is a segfault, not an exception.
     * `onDispose` covers the composition going away; this covers everything else.
     */
    private val shutdownHook =
        Thread(::close, "gst-video-shutdown").also {
            Runtime.getRuntime().addShutdownHook(it)
        }

    /** True when a new frame reached the texture — signal the controller then. */
    fun pullFrame(): Boolean =
        synchronized(lock) {
            if (closed) false else NativeGstVideoBridge.nativePullFrame(handle) == 1
        }

    /** Mutes playbin's audio path. The clock keeps running so video pacing is
     * unaffected; a file without audio ignores this. */
    var muted: Boolean = false
        set(value) {
            field = value
            synchronized(lock) {
                if (!closed) NativeGstVideoBridge.nativeSetMuted(handle, value)
            }
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            NativeGstVideoBridge.nativeClose(handle)
        }
        // Not from the hook itself: removing a running hook throws.
        if (Thread.currentThread() !== shutdownHook) {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }

    companion object {
        /** Whether the sample's helper library is available at all. */
        val isAvailable: Boolean get() = NativeGstVideoBridge.isLoaded

        /**
         * Opens [uri] and prerolls it, or returns null when the helper is missing,
         * the URI is undecodable with the installed plugins, or no EGL context is
         * current (see the class note).
         */
        fun open(uri: String): GstVideoTexture? {
            if (!NativeGstVideoBridge.isLoaded) return null
            val handle = NativeGstVideoBridge.nativeOpen(uri)
            if (handle == 0L) return null
            val widthPx = NativeGstVideoBridge.nativeWidth(handle)
            val heightPx = NativeGstVideoBridge.nativeHeight(handle)
            val image = NativeGstVideoBridge.nativeEglImage(handle)
            if (widthPx < 1 || heightPx < 1 || image == 0L) {
                NativeGstVideoBridge.nativeClose(handle)
                return null
            }
            val hasAudio = NativeGstVideoBridge.nativeHasAudio(handle)
            return GstVideoTexture(
                handle,
                widthPx,
                heightPx,
                nucleusEglImageTextureSource(image, widthPx, heightPx),
                hasAudio,
            )
        }
    }
}
