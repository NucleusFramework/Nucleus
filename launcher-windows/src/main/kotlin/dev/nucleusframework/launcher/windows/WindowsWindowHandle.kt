package dev.nucleusframework.launcher.windows

import java.awt.Window

/**
 * Resolves the Win32 `HWND` backing an AWT window.
 *
 * The launcher APIs ([WindowsOverlayIcon], [WindowsThumbnailToolbar]) address
 * windows by raw `HWND`, so they work with any windowing backend (AWT, Tao).
 * This helper covers the AWT side; non-AWT backends expose their `HWND`
 * directly (e.g. `TaoWindow.nativeHandle`).
 */
public object WindowsWindowHandle {
    /**
     * @return the `HWND` of [window] as a `Long`, or 0 if the native library
     *   is unavailable or the window has no realized peer yet.
     */
    public fun of(window: Window): Long =
        if (NativeWindowsTaskbarBridge.isLoaded) {
            NativeWindowsTaskbarBridge.nativeGetHwnd(window)
        } else {
            0L
        }
}
