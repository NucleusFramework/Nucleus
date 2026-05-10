package dev.nucleusframework.nucleus.media.control

/**
 * Events sent by the OS media controls to the application.
 */
sealed class MediaControlEvent {
    /** Request to start playback. */
    data object Play : MediaControlEvent()

    /** Request to pause playback. */
    data object Pause : MediaControlEvent()

    /** Request to toggle between play and pause. */
    data object Toggle : MediaControlEvent()

    /** Request to skip to the next track. */
    data object Next : MediaControlEvent()

    /** Request to skip to the previous track. */
    data object Previous : MediaControlEvent()

    /** Request to stop playback. */
    data object Stop : MediaControlEvent()

    /**
     * Request to seek relative to the current position.
     * @property offsetMs Offset in milliseconds. Negative means seek backward.
     */
    data class SeekBy(
        val offsetMs: Long,
    ) : MediaControlEvent()

    /** Request to set the playback position (absolute, in milliseconds). */
    data class SetPosition(
        val positionMs: Long,
    ) : MediaControlEvent()

    /** Request to set the volume. The value is in the range 0.0–1.0. */
    data class SetVolume(
        val volume: Double,
    ) : MediaControlEvent()

    /** Request to open a URI. */
    data class OpenUri(
        val uri: String,
    ) : MediaControlEvent()

    /** Request to bring the media player's UI to the front. */
    data object Raise : MediaControlEvent()

    /** Request to quit the media player. */
    data object Quit : MediaControlEvent()
}
