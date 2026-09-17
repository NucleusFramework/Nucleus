package dev.nucleusframework.window

// `DWM_SYSTEMBACKDROP_TYPE` wire values (dwmapi.h). Named rather than inlined
// so the enum below reads as the mapping it is.
private const val DWMSBT_AUTO = 0
private const val DWMSBT_NONE = 1
private const val DWMSBT_MAINWINDOW = 2
private const val DWMSBT_TRANSIENTWINDOW = 3
private const val DWMSBT_TABBEDWINDOW = 4

/**
 * Material Windows 11 composites behind the window — the
 * `DWM_SYSTEMBACKDROP_TYPE` values. See [WindowsBackdrop].
 */
public enum class WindowsBackdropStyle(
    // Explicit wire value for the JNI call: reordering the entries must not
    // silently swap one effect for another.
    internal val nativeValue: Int,
) {
    /** `DWMSBT_AUTO` — leave the choice to DWM (no backdrop for an ordinary window). */
    Default(DWMSBT_AUTO),

    /** `DWMSBT_NONE` — explicitly no backdrop; the window stays opaque. */
    None(DWMSBT_NONE),

    /**
     * `DWMSBT_MAINWINDOW` — Mica: an opaque, wallpaper-tinted material for
     * long-lived main windows. Windows' own Settings and File Explorer use it.
     */
    Mica(DWMSBT_MAINWINDOW),

    /**
     * `DWMSBT_TRANSIENTWINDOW` — Acrylic: a translucent blur of whatever is
     * behind the window. Meant for transient surfaces (flyouts, palettes)
     * rather than a main window.
     */
    Acrylic(DWMSBT_TRANSIENTWINDOW),

    /**
     * `DWMSBT_TABBEDWINDOW` — Mica Alt: a stronger wallpaper tint intended for
     * windows with a tab band at the top, like Windows Terminal.
     */
    MicaAlt(DWMSBT_TABBEDWINDOW),
    ;

    /** Whether this style actually draws a backdrop, i.e. needs a transparent client area. */
    internal val isActive: Boolean
        get() = nativeValue >= DWMSBT_MAINWINDOW
}
