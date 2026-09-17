package dev.nucleusframework.samplegst

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to `libnucleus_gst_video.so`, the sample's GStreamer helper.
 *
 * Not built by CI: run `src/main/native/linux/build.sh` once (it needs the
 * GStreamer development files). [isLoaded] is false until then, and the sample
 * says so rather than failing.
 */
internal object NativeGstVideoBridge {
    val isLoaded: Boolean by lazy {
        NativeLibraryLoader.load("nucleus_gst_video", NativeGstVideoBridge::class.java)
    }

    /**
     * Opens [uri] (a `file://` URI or anything `uridecodebin` understands) and
     * prerolls it. **Must run with the window's EGL context current** — from a
     * Compose `remember {}`, which executes inside the render pass — because every
     * context the helper creates shares from that one. Returns 0 on failure.
     */
    @JvmStatic
    external fun nativeOpen(uri: String): Long

    @JvmStatic
    external fun nativeWidth(handle: Long): Int

    @JvmStatic
    external fun nativeHeight(handle: Long): Int

    /** The `EGLImageKHR` to import — stable for the whole playback. */
    @JvmStatic
    external fun nativeEglImage(handle: Long): Long

    /**
     * Copies the newest decoded frame into the texture that image aliases: 1 when a
     * frame was copied, 0 when none was waiting, -1 on error. Any thread, one at a
     * time — it binds a context of its own, never the render thread's.
     */
    @JvmStatic
    external fun nativePullFrame(handle: Long): Int

    /** True when the file has an audio stream — queried live from playbin's `n-audio`. */
    @JvmStatic
    external fun nativeHasAudio(handle: Long): Boolean

    /** Mutes the audio path; a no-op for a file without audio. */
    @JvmStatic
    external fun nativeSetMuted(
        handle: Long,
        muted: Boolean,
    )

    @JvmStatic
    external fun nativeClose(handle: Long)
}
