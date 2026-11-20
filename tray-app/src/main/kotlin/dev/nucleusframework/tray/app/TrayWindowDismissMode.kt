package dev.nucleusframework.tray.app

/**
 * Defines how the tray window should be dismissed (hidden)
 */
public enum class TrayWindowDismissMode {
    /**
     * The window automatically hides when it loses focus or when clicking outside.
     * This is the traditional behavior for tray popup windows.
     */
    AUTO,

    /**
     * The window remains visible until explicitly hidden via TrayAppState.hide()
     * or by clicking the tray icon again.
     * Focus loss and outside clicks do not dismiss the window.
     */
    MANUAL,
}
