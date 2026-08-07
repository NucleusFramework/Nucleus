package dev.nucleusframework.media.control

/**
 * Events sent by the OS media controls to the application.
 */
public sealed class MediaControlEvent {
    /** Request to start playback. */
    public data object Play : MediaControlEvent()

    /** Request to pause playback. */
    public data object Pause : MediaControlEvent()

    /** Request to toggle between play and pause. */
    public data object Toggle : MediaControlEvent()

    /** Request to skip to the next track. */
    public data object Next : MediaControlEvent()

    /** Request to skip to the previous track. */
    public data object Previous : MediaControlEvent()

    /** Request to stop playback. */
    public data object Stop : MediaControlEvent()

    /**
     * Request to seek relative to the current position.
     * @property offsetMs Offset in milliseconds. Negative means seek backward.
     */
    public data class SeekBy(
        val offsetMs: Long,
    ) : MediaControlEvent()

    /** Request to set the playback position (absolute, in milliseconds). */
    public data class SetPosition(
        val positionMs: Long,
    ) : MediaControlEvent()

    /** Request to set the volume. The value is in the range 0.0–1.0. */
    public data class SetVolume(
        val volume: Double,
    ) : MediaControlEvent()

    /** Request to open a URI. */
    public data class OpenUri(
        val uri: String,
    ) : MediaControlEvent()

    /** Request to bring the media player's UI to the front. */
    public data object Raise : MediaControlEvent()

    /** Request to quit the media player. */
    public data object Quit : MediaControlEvent()
}
