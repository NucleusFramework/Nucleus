package dev.nucleusframework.media.control

/** The current playback status of the media player. */
public enum class MediaPlaybackStatus {
    STOPPED,
    PAUSED,
    PLAYING,
}

/**
 * The current playback state of the media player.
 *
 * @property status The playback status (stopped, paused, or playing).
 * @property positionMs The current playback position in milliseconds, or null if not known.
 */
public data class MediaPlaybackState(
    val status: MediaPlaybackStatus,
    val positionMs: Long? = null,
)
