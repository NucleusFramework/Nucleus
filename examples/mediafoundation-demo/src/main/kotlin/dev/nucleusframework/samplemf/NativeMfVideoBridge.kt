package dev.nucleusframework.samplemf

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to `nucleus_mf_video.dll`, the sample's Media Foundation helper.
 *
 * Not built by CI: run `src/main/native/windows/build.bat` once (it only needs
 * MSVC — everything it links against ships with Windows). [isLoaded] is false
 * until then, and the sample says so rather than failing.
 */
internal object NativeMfVideoBridge {
    val isLoaded: Boolean by lazy {
        NativeLibraryLoader.load("nucleus_mf_video", NativeMfVideoBridge::class.java)
    }

    /**
     * Opens [url] (a file path or any URL Media Foundation can resolve), sets the
     * decode chain up and decodes the first frame. Any thread — unlike the Linux
     * helper this one owns its D3D11 device, so no render context has to be
     * current. Returns 0 on failure.
     */
    @JvmStatic
    external fun nativeOpen(url: String): Long

    @JvmStatic
    external fun nativeWidth(handle: Long): Int

    @JvmStatic
    external fun nativeHeight(handle: Long): Int

    /**
     * The legacy DXGI shared handle of the frame texture — stable for the whole
     * playback, so the consumer imports it once.
     */
    @JvmStatic
    external fun nativeSharedHandle(handle: Long): Long

    /**
     * Converts the next frame into that texture when its presentation time is
     * due: 1 when a frame landed, 0 when the next one is still in the future,
     * -1 on error. Any thread, one at a time.
     */
    @JvmStatic
    external fun nativePullFrame(handle: Long): Int

    @JvmStatic
    external fun nativeClose(handle: Long)
}
