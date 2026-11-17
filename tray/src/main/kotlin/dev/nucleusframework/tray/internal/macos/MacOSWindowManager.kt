package dev.nucleusframework.tray.internal.macos

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.tray.utils.debugln

internal class MacOSWindowManager {
    // Detect platform once
    private val isMacOs: Boolean = Platform.Current == Platform.MacOS

    /**
     * Show the application in the Dock
     */
    fun showInDock(): Boolean {
        if (!isMacOs) {
            debugln { "showInDock(): non-macOS platform detected, no action performed" }
            return true
        }
        return try {
            MacNativeBridge.nativeShowInDock() == 0
        } catch (e: Exception) {
            debugln { "Error while restoring in Dock: ${e.message}" }
            false
        }
    }

    /**
     * Set the application as accessory (no Dock icon, but can have a menu)
     */
    fun hideFromDock(): Boolean {
        if (!isMacOs) {
            debugln { "hideFromDock(): non-macOS platform detected, no action performed" }
            return true
        }
        return try {
            MacNativeBridge.nativeHideFromDock() == 0
        } catch (e: Exception) {
            debugln { "Error while setting as accessory: ${e.message}" }
            false
        }
    }

    /**
     * Check if the application can be hidden from the Dock
     */
    fun canHideFromDock(): Boolean = isMacOs

    /**
     * Configure a window so that macOS moves it to the active Space when it
     * is ordered front, instead of switching back to the Space where the
     * window was originally created.
     *
     * @param nsViewPtr NSView pointer of the window's content view (on the
     * Tao backend this is `TaoWindow.nativeHandle`).
     */
    fun setMoveToActiveSpace(nsViewPtr: Long): Boolean {
        if (!isMacOs) return false
        return try {
            debugln { "[MacOSWindowManager] setMoveToActiveSpace: viewPtr=$nsViewPtr" }
            if (nsViewPtr != 0L) {
                val result = MacNativeBridge.nativeSetMoveToActiveSpaceForWindow(nsViewPtr)
                if (result == 0) return true
            }

            // Fallback: set on all app windows via native helper
            debugln { "[MacOSWindowManager] Fallback: setting moveToActiveSpace on all windows..." }
            MacNativeBridge.nativeSetMoveToActiveSpace()
            true
        } catch (e: Throwable) {
            debugln { "Failed to set moveToActiveSpace: ${e.message}" }
            false
        }
    }

    /**
     * Check if any floating-level NSWindow is on the active Space.
     * Returns true if on active Space or if check fails (fail-open).
     */
    fun isFloatingWindowOnActiveSpace(): Boolean {
        if (!isMacOs) return true
        return try {
            MacNativeBridge.nativeIsFloatingWindowOnActiveSpace() != 0
        } catch (e: Throwable) {
            debugln { "Failed to check isFloatingWindowOnActiveSpace: ${e.message}" }
            true
        }
    }

    /**
     * Check if a window is currently on the active macOS Space.
     * Returns true if on the active Space, false if on another Space.
     * Returns true by default if the check cannot be performed (fail-open).
     *
     * @param nsViewPtr NSView pointer of the window's content view.
     */
    fun isOnActiveSpace(nsViewPtr: Long): Boolean {
        if (!isMacOs) return true
        return try {
            if (nsViewPtr == 0L) return true
            MacNativeBridge.nativeIsOnActiveSpaceForView(nsViewPtr) != 0
        } catch (e: Throwable) {
            debugln { "Failed to check isOnActiveSpace: ${e.message}" }
            true // fail-open: assume on active Space
        }
    }

    /**
     * Bring the floating-level NSWindow to the front on the active Space.
     * With moveToActiveSpace collection behavior, this physically moves the window.
     * Also activates the application to ensure focus is gained.
     */
    fun bringFloatingWindowToFront(): Boolean {
        if (!isMacOs) return false
        return try {
            MacNativeBridge.nativeBringFloatingWindowToFront() == 0
        } catch (e: Throwable) {
            debugln { "Failed to bringFloatingWindowToFront: ${e.message}" }
            false
        }
    }
}
