package dev.nucleusframework.globalhotkey

/** Whether a [HotKeyEvent] reports the chord going down or coming back up. */
public enum class HotKeyState {
    /** The chord was pressed (or auto-repeated while held, see [HotKeyEvent.isRepeat]). */
    PRESSED,

    /**
     * The chord was released. Reported by the Linux backends only: the portal's `Deactivated`
     * signal on Wayland, the grabbed key's `KeyRelease` on X11.
     */
    RELEASED,
}

/**
 * One activation of a registered global hotkey, delivered to a [HotKeyEventListener].
 *
 * @property keyCode the registered AWT virtual key code.
 * @property modifiers the registered [HotKeyModifier] bitmask.
 * @property state whether the chord went down or came back up.
 * @property isRepeat `true` for a press delivered while the chord is still held: keyboard
 *   auto-repeat, which GNOME turns into one portal `Activated` signal per repeat, each with a
 *   fresh token. A press counts as one when no release came since the previous press and that
 *   press is less than a second old, so a backend that never reports releases still sees a new
 *   press after a pause. Always `false` on Windows and macOS, which do not repeat hotkeys.
 * @property timestamp when the event happened, in the platform's own millisecond clock
 *   (the portal's activation timestamp, the X server time), or 0 when the platform gives none.
 *   Only meaningful relative to other events of the same backend.
 * @property activationToken the `xdg-activation` token the Wayland compositor handed out
 *   with this event (`org.freedesktop.portal.GlobalShortcuts`, xdg-desktop-portal 1.21+), or
 *   null. A Wayland compositor only lets a window take focus with such a token: pass it to
 *   `TaoWindow.focus(activationToken)` / `NucleusWindow.requestFocus(activationToken)` to bring
 *   a window to the front in response to the hotkey. A token is single-use and short-lived —
 *   use it right away. Always null on X11, Windows and macOS, where none is needed.
 */
public class HotKeyEvent(
    public val keyCode: Int,
    public val modifiers: Int,
    public val state: HotKeyState,
    public val isRepeat: Boolean,
    public val timestamp: Long,
    public val activationToken: String?,
) {
    override fun toString(): String =
        "HotKeyEvent(keyCode=$keyCode, modifiers=$modifiers, state=$state, isRepeat=$isRepeat, " +
            "timestamp=$timestamp, activationToken=${if (activationToken == null) "null" else "<set>"})"
}

/**
 * Callback receiving every [HotKeyEvent] of a registered global hotkey: presses, auto-repeats
 * and, where the platform reports them, releases.
 *
 * Same threading contract as [HotKeyListener]: always invoked on the host's UI thread, and an
 * event still queued when [GlobalHotKeyManager.unregister] or [GlobalHotKeyManager.shutdown]
 * returns is dropped.
 */
public fun interface HotKeyEventListener {
    /** Called for each press, repeat and release of the hotkey. */
    public fun onHotKeyEvent(event: HotKeyEvent)
}
