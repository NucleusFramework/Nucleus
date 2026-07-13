package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.Platform

/**
 * Process-wide controller for the macOS Dock icon when an app opted into
 * `nucleusApplication(dockIconFollowsWindows = true)`.
 *
 * Reference-counts the visible, dock-contributing [DecoratedWindow]s (those
 * created with `hiddenFromDock = false`). The app runs as an accessory (no Dock
 * icon, no menu bar) while the count is zero and switches to a regular app
 * while at least one such window is visible — mirroring a menu-bar / agent app
 * that only appears in the Dock while it has a real window on screen. Standalone
 * tray popups ([TaoStandalonePopup]) are non-activating panels and never
 * contribute, so a tray-only app stays out of the Dock.
 *
 * No-op off macOS: Windows has no Dock, and Linux taskbar exclusion is
 * per-window via `hiddenFromDock`. Every entry point runs on the Tao main
 * thread (the Compose dispatcher), which is the macOS main thread the native
 * activation-policy switch requires — so the shared state needs no locking.
 *
 * Public because [dev.nucleusframework.application.nucleusApplication] wires the
 * opt-in from another module; not intended for direct use by applications.
 */
object TaoDockPolicy {
    private var enabled = false
    private var visibleWindowCount = 0

    /**
     * Enables (or disables) Dock-follows-windows and applies the policy for the
     * current window count. Called once at startup from the Tao launcher's root
     * composition, so a tray-only app drops out of the Dock immediately.
     */
    fun setEnabled(value: Boolean) {
        if (Platform.Current != Platform.MacOS) return
        enabled = value
        if (enabled) applyPolicy(dockVisible = visibleWindowCount > 0)
    }

    /** A dock-contributing window became visible. */
    fun onWindowShown() {
        if (Platform.Current != Platform.MacOS) return
        visibleWindowCount++
        if (enabled && visibleWindowCount == 1) applyPolicy(dockVisible = true)
    }

    /** A dock-contributing window was hidden or left the composition. */
    fun onWindowHidden() {
        if (Platform.Current != Platform.MacOS) return
        if (visibleWindowCount > 0) visibleWindowCount--
        if (enabled && visibleWindowCount == 0) applyPolicy(dockVisible = false)
    }

    private fun applyPolicy(dockVisible: Boolean) {
        if (NativeTaoMacOsDecoBridge.isLoaded) {
            NativeTaoMacOsDecoBridge.nativeSetHiddenFromDock(!dockVisible)
        }
    }
}
