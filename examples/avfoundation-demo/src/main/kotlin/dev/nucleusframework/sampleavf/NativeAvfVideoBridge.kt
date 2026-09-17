package dev.nucleusframework.sampleavf

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to `libnucleus_avf_video.dylib`, the sample's AVFoundation helper.
 *
 * Not built by CI: run `src/main/native/macos/build.sh` once (it only needs the
 * Xcode command-line tools — everything it links against ships with macOS).
 * [isLoaded] is false until then, and the sample says so rather than failing.
 */
internal object NativeAvfVideoBridge {
    val isLoaded: Boolean by lazy {
        NativeLibraryLoader.load("nucleus_avf_video", NativeAvfVideoBridge::class.java)
    }

    /**
     * Opens [url] (a file path or any URL AVFoundation can resolve), sets the
     * decode chain up and decodes the first frame. Any thread — this helper owns
     * its own Metal device, so no render context has to be current. Returns 0 on
     * failure.
     */
    @JvmStatic
    external fun nativeOpen(url: String): Long

    @JvmStatic
    external fun nativeWidth(handle: Long): Int

    @JvmStatic
    external fun nativeHeight(handle: Long): Int

    /** Whether the opened asset has a sound track (the picture always plays). */
    @JvmStatic
    external fun nativeAudioEnabled(handle: Long): Boolean

    /**
     * The `IOSurfaceRef` of the frame texture — stable for the whole playback,
     * so the consumer imports it once. Handed over as its raw pointer.
     */
    @JvmStatic
    external fun nativeIoSurface(handle: Long): Long

    /**
     * Converts the next frame into that surface when its presentation time is
     * due: 1 when a frame landed, 0 when the next one is still in the future,
     * -1 on error. Any thread, one at a time.
     */
    @JvmStatic
    external fun nativePullFrame(handle: Long): Int

    @JvmStatic
    external fun nativeClose(handle: Long)

    /**
     * Sets the audio volume in `[0, 1]`. No-op when the asset has no audio track.
     * Any thread.
     */
    @JvmStatic
    external fun nativeSetVolume(
        handle: Long,
        volume: Float,
    )

    /**
     * Mutes or unmutes the audio. No-op when the asset has no audio track. Any
     * thread.
     */
    @JvmStatic
    external fun nativeSetMuted(
        handle: Long,
        muted: Boolean,
    )
}
