package dev.nucleusframework.lab.probes.rendering.video

import dev.nucleusframework.core.runtime.NativeLibraryLoader

// JNI bridges to the probe's helper libraries (src/main/native/<os>), sample code outside
// CI: `:examples:lab:probes:rendering:buildRenderingNatives` builds the host OS's one.
// Unless noted, every call is safe from any thread, one at a time per handle.

/** `libnucleus_lab_gst_video.so`: GStreamer, `glcolorconvert`, frame as an `EGLImage`. */
internal object NativeGstVideoBridge {
    val isLoaded: Boolean by lazy {
        NativeLibraryLoader.load(
            "nucleus_lab_gst_video",
            NativeGstVideoBridge::class.java,
        )
    }

    /** Opens and prerolls [uri]. **Needs the window's EGL context current**: call from a draw pass. 0 on failure. */
    @JvmStatic external fun nativeOpen(uri: String): Long

    @JvmStatic external fun nativeWidth(handle: Long): Int

    @JvmStatic external fun nativeHeight(handle: Long): Int

    /** The `EGLImageKHR` to import, stable for the whole playback. */
    @JvmStatic external fun nativeEglImage(handle: Long): Long

    /** 1 when a frame was copied in, 0 when none was waiting, -1 on error. */
    @JvmStatic external fun nativePullFrame(handle: Long): Int

    @JvmStatic external fun nativeHasAudio(handle: Long): Boolean

    @JvmStatic external fun nativeSetMuted(
        handle: Long,
        muted: Boolean,
    )

    @JvmStatic external fun nativeClose(handle: Long)
}

/** `nucleus_lab_mf_video.dll`: Media Foundation + DXVA, video processor, shared D3D11 texture. */
internal object NativeMfVideoBridge {
    val isLoaded: Boolean by lazy { NativeLibraryLoader.load("nucleus_lab_mf_video", NativeMfVideoBridge::class.java) }

    /** Opens [url] on the helper's own D3D11 device and decodes the first frame. Blocks. 0 on failure. */
    @JvmStatic external fun nativeOpen(url: String): Long

    @JvmStatic external fun nativeWidth(handle: Long): Int

    @JvmStatic external fun nativeHeight(handle: Long): Int

    /** The legacy DXGI shared handle of the frame texture, stable for the whole playback. */
    @JvmStatic external fun nativeSharedHandle(handle: Long): Long

    /** 1 when a due frame landed, 0 when the next one is still in the future, -1 on error. */
    @JvmStatic external fun nativePullFrame(handle: Long): Int

    @JvmStatic external fun nativeHasAudio(handle: Long): Boolean

    @JvmStatic external fun nativeSetMuted(
        handle: Long,
        muted: Boolean,
    )

    @JvmStatic external fun nativeClose(handle: Long)
}

/** `libnucleus_lab_avf_video.dylib`: AVFoundation + VideoToolbox, Metal conversion, `IOSurface`. */
internal object NativeAvfVideoBridge {
    val isLoaded: Boolean by lazy {
        NativeLibraryLoader.load(
            "nucleus_lab_avf_video",
            NativeAvfVideoBridge::class.java,
        )
    }

    /** Opens [url] on the helper's own Metal device and decodes the first frame. Blocks. 0 on failure. */
    @JvmStatic external fun nativeOpen(url: String): Long

    @JvmStatic external fun nativeWidth(handle: Long): Int

    @JvmStatic external fun nativeHeight(handle: Long): Int

    @JvmStatic external fun nativeAudioEnabled(handle: Long): Boolean

    /** The `IOSurfaceRef` of the frame texture, stable for the whole playback. */
    @JvmStatic external fun nativeIoSurface(handle: Long): Long

    /** 1 when a due frame landed, 0 when the next one is still in the future, -1 on error. */
    @JvmStatic external fun nativePullFrame(handle: Long): Int

    @JvmStatic external fun nativeSetMuted(
        handle: Long,
        muted: Boolean,
    )

    @JvmStatic external fun nativeClose(handle: Long)
}
