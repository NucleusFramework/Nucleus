package dev.nucleusframework.media.control

/**
 * Media metadata for the system media center.
 *
 * @property title The track title, or null if not available.
 * @property artist The artist name, or null if not available.
 * @property album The album name, or null if not available.
 * @property coverUrl A URL to the cover art. On Linux (MPRIS), local files must use the `file://` scheme.
 * @property duration The track duration in milliseconds, or null if not available.
 */
public data class MediaMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val coverUrl: String? = null,
    val duration: Long? = null,
)
