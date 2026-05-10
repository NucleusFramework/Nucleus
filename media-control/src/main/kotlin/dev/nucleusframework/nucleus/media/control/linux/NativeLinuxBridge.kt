package dev.nucleusframework.nucleus.media.control.linux

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_media_control_linux"

internal object NativeLinuxBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeLinuxBridge::class.java)
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
    // Invoked on a native D-Bus thread. The caller is responsible for
    // dispatching to the UI thread if needed.

    @JvmStatic
    fun onMediaControlEvent(eventJson: String) {
        userCallback?.invoke(eventJson)
    }
}
