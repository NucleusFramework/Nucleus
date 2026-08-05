package dev.nucleusframework.samplemf

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TextureViewSource
import dev.nucleusframework.window.tao.nucleusD3D11SharedTextureSource

/**
 * A Media Foundation decode chain feeding one [TextureViewSource]: DXVA2
 * hardware decode, colour conversion by the D3D11 video processor, and the
 * frame handed over as a shared D3D11 texture the compositor samples.
 *
 * The decoder's output is NV12, which Skia cannot sample, so the video
 * processor turns it into RGBA **on the GPU** — the same division of labour
 * as the GStreamer sample on Linux, and the one Chromium's Windows video
 * path uses. It converts into a texture of the helper's own, so the source
 * stays the same object for the whole playback: one import, and not a single
 * recomposition per frame.
 *
 * Unlike the Linux helper, this one owns its D3D11 device: [open] blocks for
 * the first frame but needs no render context, so it belongs on a background
 * dispatcher rather than inside a draw pass.
 *
 * [pullFrame] is meant for a producer loop on a background dispatcher: the
 * helper paces frames against the file's own timeline, so calling it once per
 * composited frame plays the video at its real rate whatever the display
 * does. Serialised here, since the decode chain is one pipeline.
 */
class MfVideoTexture private constructor(
    private val handle: Long,
    val widthPx: Int,
    val heightPx: Int,
    val source: TextureViewSource,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    /**
     * A decode chain outlives an abrupt exit, and Media Foundation's worker
     * threads then touch a device the toolkit has already torn down.
     * `onDispose` covers the composition going away; this covers the rest.
     */
    private val shutdownHook =
        Thread(::close, "mf-video-shutdown").also {
            Runtime.getRuntime().addShutdownHook(it)
        }

    /** True when a new frame reached the texture — signal the controller then. */
    fun pullFrame(): Boolean =
        synchronized(lock) {
            if (closed) false else NativeMfVideoBridge.nativePullFrame(handle) == 1
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            NativeMfVideoBridge.nativeClose(handle)
        }
        // Not from the hook itself: removing a running hook throws.
        if (Thread.currentThread() !== shutdownHook) {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }

    companion object {
        /** Whether the sample's helper library is available at all. */
        val isAvailable: Boolean
            get() = Platform.Current == Platform.Windows && NativeMfVideoBridge.isLoaded

        /**
         * Opens [url] and decodes its first frame, or returns null when the
         * helper is missing, the file is undecodable with the installed codecs,
         * or the adapter has no video processor. Blocks — call it off the UI
         * thread.
         */
        fun open(url: String): MfVideoTexture? {
            if (!isAvailable) return null
            val handle = NativeMfVideoBridge.nativeOpen(url)
            if (handle == 0L) return null
            val widthPx = NativeMfVideoBridge.nativeWidth(handle)
            val heightPx = NativeMfVideoBridge.nativeHeight(handle)
            val sharedHandle = NativeMfVideoBridge.nativeSharedHandle(handle)
            if (widthPx < 1 || heightPx < 1 || sharedHandle == 0L) {
                NativeMfVideoBridge.nativeClose(handle)
                return null
            }
            return MfVideoTexture(
                handle,
                widthPx,
                heightPx,
                nucleusD3D11SharedTextureSource(sharedHandle, widthPx, heightPx),
            )
        }
    }
}
