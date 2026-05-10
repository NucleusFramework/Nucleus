package dev.nucleusframework.nucleus.media.control.windows

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_media_control_windows"

internal object NativeWindowsBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeWindowsBridge::class.java)
    val isLoaded: Boolean get() = loaded

    @Volatile
    private var userCallback: ((String) -> Unit)? = null

    fun attach(callback: (String) -> Unit): Boolean {
        userCallback = callback
        if (!isLoaded) return false
        return nativeStartListening()
    }

    fun detach() {
        if (isLoaded) {
            nativeStopListening()
        }
        userCallback = null
    }

    // ---- Native methods ------------------------------------------------

    @JvmStatic
    external fun nativeConfigure(
        dbusName: String,
        displayName: String,
    )

    @JvmStatic
    external fun nativeSetMetadata(
        title: String?,
        artist: String?,
        album: String?,
        coverUrl: String?,
        durationMs: Long,
    )

    @JvmStatic
    external fun nativeSetPlaybackState(
        status: Int,
        positionMs: Long,
    )

    @JvmStatic
    external fun nativeSetVolume(volume: Double)

    @JvmStatic
    external fun nativeStartListening(): Boolean

    @JvmStatic
    external fun nativeStopListening()

    // ---- Callback from native -------------------------------------------
    // Invoked on a native thread. The caller is responsible for dispatching
    // to the UI thread if needed.

    @JvmStatic
    fun onMediaControlEvent(eventJson: String) {
        userCallback?.invoke(eventJson)
    }
}
