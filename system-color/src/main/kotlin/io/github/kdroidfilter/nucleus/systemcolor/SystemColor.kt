package io.github.kdroidfilter.nucleus.systemcolor

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.systemcolor.linux.isLinuxAccentColorSupported
import io.github.kdroidfilter.nucleus.systemcolor.linux.linuxAccentColor
import io.github.kdroidfilter.nucleus.systemcolor.linux.linuxHighContrast
import io.github.kdroidfilter.nucleus.systemcolor.mac.MacSystemColorDetector
import io.github.kdroidfilter.nucleus.systemcolor.mac.isMacOsInHighContrast
import io.github.kdroidfilter.nucleus.systemcolor.mac.macOsAccentColor
import io.github.kdroidfilter.nucleus.systemcolor.windows.WindowsSystemColorDetector
import io.github.kdroidfilter.nucleus.systemcolor.windows.windowsAccentColor
import io.github.kdroidfilter.nucleus.systemcolor.windows.windowsHighContrast

/**
 * Eagerly triggers the JNI library load on the calling thread.
 *
 * On macOS the first `dlopen` can take 100–300 ms (AMFI code-signature
 * validation). Call this from a background daemon thread during `main()` to
 * keep the UI thread responsive on first accent-color query.
 */
fun preloadSystemColor() {
    isSystemAccentColorSupported()
}

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
