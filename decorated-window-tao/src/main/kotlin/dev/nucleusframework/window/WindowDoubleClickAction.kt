package dev.nucleusframework.window

/**
 * Action performed when the user double-clicks a [windowDragArea].
 */
public enum class WindowDoubleClickAction {
    /**
     * Toggle the window's maximized state, matching the built-in `TitleBar`
     * gesture. Gated on the window being resizable (or already maximized) and
     * suppressed while fullscreen.
     */
    ToggleMaximize,

    /** Double-clicks are ignored. */
    None,
}
