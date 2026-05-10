package dev.nucleusframework.nucleus.systemcolor

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.nucleus.core.runtime.Platform
import dev.nucleusframework.nucleus.systemcolor.linux.isLinuxAccentColorSupported
import dev.nucleusframework.nucleus.systemcolor.linux.linuxAccentColor
import dev.nucleusframework.nucleus.systemcolor.linux.linuxHighContrast
import dev.nucleusframework.nucleus.systemcolor.mac.MacSystemColorDetector
import dev.nucleusframework.nucleus.systemcolor.mac.isMacOsInHighContrast
import dev.nucleusframework.nucleus.systemcolor.mac.macOsAccentColor
import dev.nucleusframework.nucleus.systemcolor.windows.WindowsSystemColorDetector
import dev.nucleusframework.nucleus.systemcolor.windows.windowsAccentColor
import dev.nucleusframework.nucleus.systemcolor.windows.windowsHighContrast

/**
 * Returns whether the current platform supports system accent color detection.
 */
fun isSystemAccentColorSupported(): Boolean =
    when (Platform.Current) {
        Platform.MacOS -> MacSystemColorDetector.isAccentColorSupported()
        Platform.Windows -> WindowsSystemColorDetector.isAccentColorSupported()
        Platform.Linux -> isLinuxAccentColorSupported()
        else -> false
    }

/**
 * Composable that reactively returns the system accent color.
 * Returns null if the platform does not support accent color detection.
 * Automatically updates when the user changes the system accent color.
 */
@Composable
fun systemAccentColor(): Color? =
    when (Platform.Current) {
        Platform.MacOS -> macOsAccentColor()
        Platform.Windows -> windowsAccentColor()
        Platform.Linux -> linuxAccentColor()
        else -> null
    }

/**
 * Composable that reactively returns whether the system is in high contrast mode.
 * Automatically updates when the user toggles the accessibility contrast setting.
 */
@Composable
fun isSystemInHighContrast(): Boolean =
    when (Platform.Current) {
        Platform.MacOS -> isMacOsInHighContrast()
        Platform.Windows -> windowsHighContrast()
        Platform.Linux -> linuxHighContrast()
        else -> false
    }
