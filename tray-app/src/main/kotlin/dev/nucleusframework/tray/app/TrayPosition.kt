package dev.nucleusframework.tray.app

import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.tray.internal.MacTrayInitializer
import dev.nucleusframework.tray.internal.WindowsTrayInitializer

/** Screen corner holding the notification area / menu bar extras, where TrayApp anchors its popup. */
public enum class TrayPosition { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

internal fun getWindowsTrayPosition(nativeResult: String?): TrayPosition =
    when (nativeResult) {
        null -> throw IllegalArgumentException("Returned value is null")
        "top-left" -> TrayPosition.TOP_LEFT
        "top-right" -> TrayPosition.TOP_RIGHT
        "bottom-left" -> TrayPosition.BOTTOM_LEFT
        "bottom-right" -> TrayPosition.BOTTOM_RIGHT
        else -> throw IllegalArgumentException("Unknown value: $nativeResult")
    }

internal fun getMacTrayPosition(nativeResult: String?): TrayPosition =
    when (nativeResult) {
        "top-left" -> TrayPosition.TOP_LEFT
        "top-right" -> TrayPosition.TOP_RIGHT
        else -> TrayPosition.TOP_RIGHT
    }

/**
 * OS → tray corner heuristic.
 *
 * Windows/macOS resolve the corner from the native tray region; Linux uses the desktop-environment
 * convention. Lives in the TrayApp (`dev.nucleusframework.tray.app`) alongside the popup positioning it feeds.
 */
public fun getTrayPosition(): TrayPosition =
    when (Platform.Current) {
        Platform.Windows -> getWindowsTrayPosition(WindowsTrayInitializer.notificationIconsRegion())
        Platform.MacOS -> getMacTrayPosition(MacTrayInitializer.statusItemRegion())
        Platform.Linux ->
            when (LinuxDesktopEnvironment.Current) {
                LinuxDesktopEnvironment.KDE -> TrayPosition.BOTTOM_RIGHT
                LinuxDesktopEnvironment.Cinnamon -> TrayPosition.BOTTOM_RIGHT
                LinuxDesktopEnvironment.Gnome -> TrayPosition.TOP_RIGHT
                LinuxDesktopEnvironment.Mate -> TrayPosition.TOP_RIGHT
                LinuxDesktopEnvironment.XFCE -> TrayPosition.TOP_RIGHT
                else -> TrayPosition.TOP_RIGHT
            }
        Platform.Unknown -> TrayPosition.TOP_RIGHT
        else -> TrayPosition.TOP_RIGHT
    }
