package io.github.kdroidfilter.nucleus.media.control

import io.github.kdroidfilter.nucleus.core.runtime.ExecutableRuntime
import io.github.kdroidfilter.nucleus.core.runtime.NucleusApp
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.media.control.linux.NativeLinuxBridge
import io.github.kdroidfilter.nucleus.media.control.macos.NativeMacOsBridge
import io.github.kdroidfilter.nucleus.media.control.windows.NativeWindowsBridge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.swing.SwingUtilities

/**
 * Entry point for OS-level media controls.
 *
 * Supported backends:
 *  - Linux: MPRIS D-Bus (`org.mpris.MediaPlayer2.<name>`)
 *  - macOS: MPNowPlayingInfoCenter + MPRemoteCommandCenter (Control Center / Now Playing)
 *  - Windows: System Media Transport Controls (SMTC / WinRT)
 *
 * Events dispatched to the callback are delivered on the Swing EDT.
 */
object MediaControlService {
    private val json = Json { ignoreUnknownKeys = true }

    private val backend: Backend =
        when (Platform.Current) {
            Platform.Linux -> LinuxBackend.takeIf { it.isLoaded } ?: NoopBackend
            Platform.MacOS -> MacOsBackend.takeIf { it.isLoaded } ?: NoopBackend
            Platform.Windows -> WindowsBackend.takeIf { it.isLoaded } ?: NoopBackend
            else -> NoopBackend
        }

    /**
     * Returns `true` if the native backend loaded successfully on the current OS.
     * Always returns `false` on Windows and unsupported platforms.
     */
    fun isAvailable(): Boolean = backend !== NoopBackend

    /**
     * Eagerly triggers the JNI library load on the calling thread.
     *
     * On macOS the first `dlopen` can take 100–300 ms (AMFI code-signature
     * validation). Call [preload] from a background daemon thread during
     * `main()` to keep the UI thread responsive on first tab open.
     */
    fun preload() {
        backend
    }

    /**
     * Configure the media player identity.
     *
     * On Linux, registers the MPRIS D-Bus name (format `org.mpris.MediaPlayer2.<name>`).
     * On macOS, this is a no-op — identity is derived from the host app bundle.
     *
     * @param dbusName The D-Bus bus name (Linux only). Defaults to `org.mpris.MediaPlayer2.${NucleusApp.appId}`.
     * @param displayName The human-readable name shown in the system media center.
     *                    Defaults to [NucleusApp.appName] or `"Nucleus App"`.
     */
    fun configure(
        dbusName: String = "org.mpris.MediaPlayer2.${NucleusApp.appId}",
        displayName: String = NucleusApp.appName ?: "Nucleus App",
    ) {
        backend.configure(dbusName, displayName)
    }

    /**
     * Update the metadata shown in the system media center.
     * Call whenever the track changes.
     */
    fun setMetadata(metadata: MediaMetadata) {
        backend.setMetadata(
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            coverUrl = metadata.coverUrl,
            durationMs = metadata.duration ?: -1L,
        )
    }

    /**
     * Update the playback state shown in the system media center.
     * Call on play/pause/stop and periodically during playback to update position.
     */
    fun setPlaybackState(state: MediaPlaybackState) {
        backend.setPlaybackState(
            status = state.status.ordinal,
            positionMs = state.positionMs ?: -1L,
        )
    }

    /**
     * Update the player volume shown in the system media center.
     *
     * @param volume Volume level in the range 0.0–1.0.
     *
     * Note: on macOS this is a no-op (no per-app volume channel in Now Playing).
     */
    fun setVolume(volume: Double) {
        backend.setVolume(volume.coerceIn(0.0, 1.0))
    }

    /**
     * Listen for control events from the OS (play, pause, seek, next, previous...).
     *
     * The callback is dispatched on the Swing EDT — safe to mutate Compose/Swing state directly.
     * Only one listener is active at a time; calling attach replaces any previous listener.
     *
     * Events emitted per platform:
     *  - Linux (MPRIS): Play, Pause, Toggle, Next, Previous, Stop, SeekBy, SetPosition, SetVolume, OpenUri, Raise, Quit
     *  - macOS (Remote Command Center): Play, Pause, Toggle, Next, Previous, Stop, SetPosition
     *  - Windows (SMTC): Play, Pause, Next, Previous, Stop, SeekBy, SetPosition
     */
    fun attach(callback: (MediaControlEvent) -> Unit) {
        backend.attach { raw ->
            val event = parseEvent(raw) ?: return@attach
            SwingUtilities.invokeLater { callback(event) }
        }
    }

    /**
     * Detach the listener and unregister from the platform media center.
     * No further events will be dispatched.
     */
    fun detach() {
        backend.detach()
    }

    @Serializable
    private data class EventDto(
        val type: String,
        val offsetUs: Long? = null,
        val positionUs: Long? = null,
        val volume: Double? = null,
        val uri: String? = null,
    )

    private fun parseEvent(raw: String): MediaControlEvent? {
        val dto = runCatching { json.decodeFromString<EventDto>(raw) }.getOrNull() ?: return null
        return when (dto.type) {
            "play" -> MediaControlEvent.Play
            "pause" -> MediaControlEvent.Pause
            "toggle" -> MediaControlEvent.Toggle
            "next" -> MediaControlEvent.Next
            "previous" -> MediaControlEvent.Previous
            "stop" -> MediaControlEvent.Stop
            "seek" -> dto.offsetUs?.let { MediaControlEvent.SeekBy(it / MICROS_PER_MS) }
            "set_position" -> dto.positionUs?.let { MediaControlEvent.SetPosition(it / MICROS_PER_MS) }
            "set_volume" -> dto.volume?.let { MediaControlEvent.SetVolume(it) }
            "open_uri" -> dto.uri?.let { MediaControlEvent.OpenUri(it) }
            "raise" -> MediaControlEvent.Raise
            "quit" -> MediaControlEvent.Quit
            else -> null
        }
    }

    private const val MICROS_PER_MS = 1000L

    // ---- Backend abstraction ------------------------------------------------

    private interface Backend {
        val isLoaded: Boolean

        fun configure(
            dbusName: String,
            displayName: String,
        )

        fun setMetadata(
            title: String?,
            artist: String?,
            album: String?,
            coverUrl: String?,
            durationMs: Long,
        )

        fun setPlaybackState(
            status: Int,
            positionMs: Long,
        )

        fun setVolume(volume: Double)

        fun attach(rawCallback: (String) -> Unit)

        fun detach()
    }

    private object LinuxBackend : Backend {
        override val isLoaded: Boolean get() = NativeLinuxBridge.isLoaded

        override fun configure(
            dbusName: String,
            displayName: String,
        ) = NativeLinuxBridge.nativeConfigure(dbusName, displayName)

        override fun setMetadata(
            title: String?,
            artist: String?,
            album: String?,
            coverUrl: String?,
            durationMs: Long,
        ) = NativeLinuxBridge.nativeSetMetadata(title, artist, album, coverUrl, durationMs)

        override fun setPlaybackState(
            status: Int,
            positionMs: Long,
        ) = NativeLinuxBridge.nativeSetPlaybackState(status, positionMs)

        override fun setVolume(volume: Double) = NativeLinuxBridge.nativeSetVolume(volume)

        override fun attach(rawCallback: (String) -> Unit) {
            NativeLinuxBridge.attach(rawCallback)
        }

        override fun detach() = NativeLinuxBridge.detach()
    }

    private object MacOsBackend : Backend {
        override val isLoaded: Boolean get() = NativeMacOsBridge.isLoaded

        override fun configure(
            dbusName: String,
            displayName: String,
        ) = NativeMacOsBridge.nativeConfigure(dbusName, displayName)

        override fun setMetadata(
            title: String?,
            artist: String?,
            album: String?,
            coverUrl: String?,
            durationMs: Long,
        ) = NativeMacOsBridge.nativeSetMetadata(title, artist, album, coverUrl, durationMs)

        override fun setPlaybackState(
            status: Int,
            positionMs: Long,
        ) = NativeMacOsBridge.nativeSetPlaybackState(status, positionMs)

        override fun setVolume(volume: Double) = NativeMacOsBridge.nativeSetVolume(volume)

        override fun attach(rawCallback: (String) -> Unit) {
            NativeMacOsBridge.attach(rawCallback)
        }

        override fun detach() = NativeMacOsBridge.detach()
    }

    private object WindowsBackend : Backend {
        override val isLoaded: Boolean get() = NativeWindowsBridge.isLoaded

        override fun configure(
            dbusName: String,
            displayName: String,
        ) {
            // Windows needs the same AUMID the Nucleus plugin stamps on the
            // Start Menu shortcut ("com.app.<packageName>") so SMTC resolves
            // it to the app icon + display name. For APPX packages the
            // package manifest provides the identity — pass empty to let
            // Windows use it instead of overriding.
            val aumid = if (ExecutableRuntime.isAppX()) "" else NucleusApp.aumid
            NativeWindowsBridge.nativeConfigure(aumid, displayName)
        }

        override fun setMetadata(
            title: String?,
            artist: String?,
            album: String?,
            coverUrl: String?,
            durationMs: Long,
        ) = NativeWindowsBridge.nativeSetMetadata(title, artist, album, coverUrl, durationMs)

        override fun setPlaybackState(
            status: Int,
            positionMs: Long,
        ) = NativeWindowsBridge.nativeSetPlaybackState(status, positionMs)

        override fun setVolume(volume: Double) = NativeWindowsBridge.nativeSetVolume(volume)

        override fun attach(rawCallback: (String) -> Unit) {
            NativeWindowsBridge.attach(rawCallback)
        }

        override fun detach() = NativeWindowsBridge.detach()
    }

    @Suppress("EmptyFunctionBlock")
    private object NoopBackend : Backend {
        override val isLoaded: Boolean get() = false

        override fun configure(
            dbusName: String,
            displayName: String,
        ) = Unit

        override fun setMetadata(
            title: String?,
            artist: String?,
            album: String?,
            coverUrl: String?,
            durationMs: Long,
        ) = Unit

        override fun setPlaybackState(
            status: Int,
            positionMs: Long,
        ) = Unit

        override fun setVolume(volume: Double) = Unit

        override fun attach(rawCallback: (String) -> Unit) = Unit

        override fun detach() = Unit
    }
}
