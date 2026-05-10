package dev.nucleusframework.nucleus.globalhotkey

/**
 * Well-known media keys that can be registered as global hotkeys.
 *
 * @param nativeCode platform-specific virtual key code used by the native bridge.
 */
@Suppress("MagicNumber")
enum class MediaKey(
    internal val nativeCode: Int,
) {
    /** Play/Pause toggle. */
    PLAY_PAUSE(0xB3),

    /** Stop playback. */
    STOP(0xB2),

    /** Next track. */
    NEXT_TRACK(0xB0),

    /** Previous track. */
    PREV_TRACK(0xB1),
}
