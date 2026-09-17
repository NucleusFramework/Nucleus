package dev.nucleusframework.sampleavf

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TextureViewSource
import dev.nucleusframework.window.tao.nucleusIOSurfaceTextureSource

/**
 * An AVFoundation decode chain feeding one [TextureViewSource]: VideoToolbox
 * hardware decode, Y'CbCr → RGBA by a Metal full-screen quad on a private
 * queue, and the frame handed over as an `IOSurface` the compositor maps on
 * the window's own Metal device — the macOS counterpart of the D3D11 video
 * processor on Windows and of `glcolorconvert` on Linux, and what
 * AVFoundation-based players do too.
 *
 * The decoder's output is bi-planar NV12, which Skia cannot sample, so the
 * conversion happens **on the GPU** — no CPU copy — into a texture of the
 * helper's own, so the source stays the same object for the whole playback:
 * one import, and not a single recomposition per frame.
 *
 * Like the Windows helper and unlike the Linux one, this owns its Metal
 * device: [open] blocks for the first frame but needs no render context, so
 * it belongs on a background dispatcher rather than inside a draw pass.
 *
 * [pullFrame] is meant for a producer loop on a background dispatcher: the
 * helper paces frames against the file's own timeline, so calling it once per
 * composited frame plays the video at its real rate whatever the display
 * does. Serialised here, since the decode chain is one pipeline.
 */
class AvfVideoTexture private constructor(
    private val handle: Long,
    val widthPx: Int,
    val heightPx: Int,
    val source: TextureViewSource,
    /** Whether the asset had a sound track (the picture always plays). */
    val audioEnabled: Boolean,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    /**
     * A decode chain outlives an abrupt exit, and AVFoundation's reader queue
     * then touches Metal objects the toolkit has already torn down.
     * `onDispose` covers the composition going away; this covers the rest.
     */
    private val shutdownHook =
        Thread(::close, "avf-video-shutdown").also {
            Runtime.getRuntime().addShutdownHook(it)
        }

    /** True when a new frame reached the texture — signal the controller then. */
    fun pullFrame(): Boolean =
        synchronized(lock) {
            if (closed) false else NativeAvfVideoBridge.nativePullFrame(handle) == 1
        }

    /**
     * Audio volume in `[0, 1]`. No-op when [audioEnabled] is false. Any thread.
     */
    fun setVolume(volume: Float) {
        if (closed || !audioEnabled) return
        NativeAvfVideoBridge.nativeSetVolume(handle, volume.coerceIn(0f, 1f))
    }

    /** Mutes or unmutes the audio. No-op when [audioEnabled] is false. Any thread. */
    fun setMuted(muted: Boolean) {
        if (closed || !audioEnabled) return
        NativeAvfVideoBridge.nativeSetMuted(handle, muted)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            NativeAvfVideoBridge.nativeClose(handle)
        }
        // Not from the hook itself: removing a running hook throws.
        if (Thread.currentThread() !== shutdownHook) {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }

    companion object {
        /** Whether the sample's helper library is available at all. */
        val isAvailable: Boolean
            get() = Platform.Current == Platform.MacOS && NativeAvfVideoBridge.isLoaded

        /**
         * Opens [url] and decodes its first frame, or returns null when the
         * helper is missing, the file is undecodable with the installed codecs,
         * or Metal is unavailable. Blocks — call it off the UI thread.
         */
        fun open(url: String): AvfVideoTexture? {
            if (!isAvailable) return null
            val handle = NativeAvfVideoBridge.nativeOpen(url)
            if (handle == 0L) return null
            val widthPx = NativeAvfVideoBridge.nativeWidth(handle)
            val heightPx = NativeAvfVideoBridge.nativeHeight(handle)
            val ioSurface = NativeAvfVideoBridge.nativeIoSurface(handle)
            if (widthPx < 1 || heightPx < 1 || ioSurface == 0L) {
                NativeAvfVideoBridge.nativeClose(handle)
                return null
            }
            val audioEnabled = NativeAvfVideoBridge.nativeAudioEnabled(handle)
            return AvfVideoTexture(
                handle,
                widthPx,
                heightPx,
                nucleusIOSurfaceTextureSource(ioSurface, widthPx, heightPx),
                audioEnabled,
            )
        }
    }
}
