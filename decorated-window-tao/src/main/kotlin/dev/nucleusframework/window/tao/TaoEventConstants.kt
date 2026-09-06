package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeTaoBridge

/** Cursor icon codes mirrored 1:1 with the Rust `cursor_from_code` table. */
@Suppress("MagicNumber")
public object TaoCursorIcon {
    public const val DEFAULT: Int = 0
    public const val TEXT: Int = 1
    public const val HAND: Int = 2
    public const val CROSSHAIR: Int = 3
    public const val WAIT: Int = 4
    public const val MOVE: Int = 5
    public const val NOT_ALLOWED: Int = 6
    public const val HELP: Int = 7
    public const val PROGRESS: Int = 8
    public const val EW_RESIZE: Int = 9
    public const val NS_RESIZE: Int = 10
    public const val NESW_RESIZE: Int = 11
    public const val NWSE_RESIZE: Int = 12
}

/** Mirrors the event constants in `nucleus_tao` (`lib.rs`). */
@Suppress("MagicNumber")
public object TaoEventCode {
    public const val LAUNCHED: Int = 1
    public const val RESIZED: Int = 2
    public const val CLOSE_REQUESTED: Int = 3
    public const val DESTROYED: Int = 4
    public const val REDRAW_REQUESTED: Int = 5
    public const val FOCUSED: Int = 6
    public const val UNFOCUSED: Int = 7
    public const val SCALE_FACTOR_CHANGED: Int = 8

    /** `a` = 1 when the window became minimized (iconified), 0 when restored. */
    public const val MINIMIZED: Int = 9

    public const val CURSOR_MOVED: Int = 10
    public const val CURSOR_LEFT: Int = 11
    public const val MOUSE_DOWN: Int = 12
    public const val MOUSE_UP: Int = 13
    public const val KEY_DOWN: Int = 14
    public const val KEY_UP: Int = 15
    public const val WINDOW_READY: Int = 16
    public const val SCROLL_LINE: Int = 17
    public const val SCROLL_PIXEL: Int = 18
    public const val KEY_TYPED: Int = 19

    /**
     * Fired once per Tao event-loop iteration once every in-flight event has
     * been processed. We use it to drain `TaoMainDispatcher`'s task queue so
     * the Compose Recomposer can run on the same thread as the Tao loop.
     */
    public const val MAIN_EVENTS_CLEARED: Int = 20

    /** `a`/`b` carry `x`/`y` in physical pixels. */
    public const val MOVED: Int = 21

    /** `a` carries the current [TaoModifierMask] bitset. */
    public const val MODIFIERS_CHANGED: Int = 22

    /**
     * Linux only. Dispatched synchronously right BEFORE the GTK window is
     * hidden, so the host can suspend EGL rendering first — on Wayland the
     * hide destroys the parent `wl_surface` and a racing swap on the owned
     * subsurface is a fatal protocol error (GDK "Error 71").
     */
    public const val WILL_HIDE: Int = 23

    /**
     * Linux only. Dispatched synchronously right AFTER the GTK window is
     * shown again (GDK surface re-created) so the host can re-attach EGL.
     */
    public const val SHOWN: Int = 24

    /**
     * Windows only. `a` = 1 when the OS modal resize/move loop starts
     * (WM_ENTERSIZEMOVE), 0 when it ends (WM_EXITSIZEMOVE). The host drops
     * VSync while active so border-drag frames don't block on VBlank.
     */
    public const val SIZE_MOVE: Int = 25
}

/** Trackpad gesture kind reported by [NativeTaoBridge.EventCallback.onTrackpadGesture]. */
@Suppress("MagicNumber")
public object TaoTrackpadGesture {
    public const val MAGNIFY: Int = 0
    public const val ROTATE: Int = 1
    public const val SMART_MAGNIFY: Int = 2
}

/** Trackpad gesture phase reported by [NativeTaoBridge.EventCallback.onTrackpadGesture]. */
@Suppress("MagicNumber")
public object TaoTrackpadPhase {
    public const val BEGAN: Int = 0
    public const val CHANGED: Int = 1
    public const val ENDED: Int = 2
    public const val CANCELLED: Int = 3
}

/**
 * Phase of a macOS trackpad scroll gesture step as delivered by
 * `EventCallback.onScrollGesture` (#654). AppKit reports the fingers-on-glass
 * part in `NSEvent.phase` and the inertial tail that follows in
 * `momentumPhase`, never both at once. [wire] is the code the Rust loop
 * (`events.rs` `SCROLL_GESTURE_*`) and the popup panel (`popup_panel.m`
 * `NucleusScrollGesture*`) send; a scroll that belongs to no gesture (wheel
 * notch, phase-less device) has no phase — `null` on the JVM,
 * [NONE_WIRE] on the popup wire. Distinct from the public
 * [TaoTrackpadPhase] of magnify / rotate gestures on purpose: the two streams
 * are different and must not be passed for one another.
 */
@Suppress("MagicNumber")
internal enum class TaoScrollGesturePhase(
    val wire: Int,
) {
    BEGAN(0),
    CHANGED(1),
    ENDED(2),
    CANCELLED(3),
    MOMENTUM_BEGAN(4),
    MOMENTUM_CHANGED(5),
    MOMENTUM_ENDED(6),

    /** Fingers touched the trackpad, no scroll yet (`NSEventPhaseMayBegin`). */
    MAY_BEGIN(7),
    ;

    companion object {
        /** Wire code for "not a gesture step" (only the popup wire carries it). */
        const val NONE_WIRE: Int = -1

        /** `null` for [NONE_WIRE] and for any code this build does not know. */
        fun fromWire(code: Int): TaoScrollGesturePhase? = entries.firstOrNull { it.wire == code }
    }
}

/** Modifier-state bitmask that mirrors the Rust side. */
@Suppress("MagicNumber")
public object TaoModifierMask {
    public const val SHIFT: Int = 1 shl 0
    public const val CONTROL: Int = 1 shl 1
    public const val ALT: Int = 1 shl 2
    public const val META: Int = 1 shl 3
}

/** AWT-equivalent `KeyEvent.KEY_LOCATION_*` constants we accept from Rust. */
@Suppress("MagicNumber")
public object TaoKeyLocation {
    public const val STANDARD: Int = 1
    public const val LEFT: Int = 2
    public const val RIGHT: Int = 3
    public const val NUMPAD: Int = 4
}

@Suppress("MagicNumber")
public object TaoMouseButton {
    public const val LEFT: Int = 0
    public const val RIGHT: Int = 1
    public const val MIDDLE: Int = 2
    public const val OTHER: Int = 3
}
