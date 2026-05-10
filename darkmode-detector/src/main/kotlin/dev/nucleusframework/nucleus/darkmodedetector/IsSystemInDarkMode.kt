package dev.nucleusframework.nucleus.darkmodedetector

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalInspectionMode
import dev.nucleusframework.nucleus.core.runtime.Platform
import dev.nucleusframework.nucleus.darkmodedetector.linux.isLinuxInDarkMode
import dev.nucleusframework.nucleus.darkmodedetector.mac.isMacOsInDarkMode
import dev.nucleusframework.nucleus.darkmodedetector.windows.isWindowsInDarkMode

/**
 * Composable function that returns whether the system is in dark mode.
 * It handles macOS, Windows, and Linux.
 */
@Composable
fun isSystemInDarkMode(): Boolean {
    val isInPreview = LocalInspectionMode.current
    if (isInPreview) {
        return isSystemInDarkTheme()
    }

    return when (Platform.Current) {
        Platform.MacOS -> isMacOsInDarkMode()
        Platform.Windows -> isWindowsInDarkMode()
        Platform.Linux -> isLinuxInDarkMode()
        else -> false
    }
}
