package dev.nucleusframework.lab.probes.shell.media

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.LabPaths
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stampedCallbackFlow
import dev.nucleusframework.media.control.MediaControlEvent
import dev.nucleusframework.media.control.MediaControlService
import dev.nucleusframework.media.control.MediaMetadata
import dev.nucleusframework.media.control.MediaPlaybackState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/** Port over `media-control` (MPRIS / Now Playing / SMTC). */
interface MediaGateway {
    fun availability(): Availability

    fun backendName(): String

    fun configure()

    fun setMetadata(metadata: MediaMetadata)

    fun setPlaybackState(state: MediaPlaybackState)

    fun setVolume(volume: Double)

    /** Commands from media keys and the OS media surfaces, while collected. */
    fun events(): Flow<Stamped<MediaControlEvent>>

    /** A local image usable as cover art (`file:` URI). */
    fun coverUri(): String?
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusMediaGateway : MediaGateway {
    private val cover: String? by lazy {
        runCatching { LabPaths.extractResource(COVER, NucleusMediaGateway::class.java).toUri().toString() }.getOrNull()
    }

    override fun availability(): Availability =
        Availability.of(MediaControlService.isAvailable()) {
            "no media control backend for this platform / native library missing"
        }

    override fun backendName(): String =
        when (Platform.Current) {
            Platform.Linux -> "MPRIS (org.mpris.MediaPlayer2 on the session bus)"
            Platform.MacOS -> "MPNowPlayingInfoCenter + MPRemoteCommandCenter"
            Platform.Windows -> "SystemMediaTransportControls"
            else -> "none"
        }

    override fun configure() = MediaControlService.configure()

    override fun setMetadata(metadata: MediaMetadata) = MediaControlService.setMetadata(metadata)

    override fun setPlaybackState(state: MediaPlaybackState) = MediaControlService.setPlaybackState(state)

    override fun setVolume(volume: Double) = MediaControlService.setVolume(volume)

    override fun events(): Flow<Stamped<MediaControlEvent>> =
        stampedCallbackFlow {
            MediaControlService.attach { emit(it) }
            onClose { MediaControlService.detach() }
        }

    override fun coverUri(): String? = cover

    private companion object {
        const val COVER = "/lab/shell/tray-icon.png"
    }
}
