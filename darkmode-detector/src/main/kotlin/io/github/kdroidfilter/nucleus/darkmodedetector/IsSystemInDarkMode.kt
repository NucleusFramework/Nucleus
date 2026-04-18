package io.github.kdroidfilter.nucleus.darkmodedetector

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalInspectionMode
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.darkmodedetector.linux.LinuxPortalThemeDetector
import io.github.kdroidfilter.nucleus.darkmodedetector.linux.isLinuxInDarkMode
import io.github.kdroidfilter.nucleus.darkmodedetector.mac.MacOSThemeDetector
import io.github.kdroidfilter.nucleus.darkmodedetector.mac.isMacOsInDarkMode
import io.github.kdroidfilter.nucleus.darkmodedetector.windows.WindowsThemeDetector
import io.github.kdroidfilter.nucleus.darkmodedetector.windows.isWindowsInDarkMode

/**
 * Eagerly triggers the JNI library load on the calling thread.
 *
 * On macOS the first `dlopen` can take 100–300 ms (AMFI code-signature
 * validation). Call this from a background daemon thread during `main()` to
 * keep the UI thread responsive on first dark-mode query.
 */
fun preloadDarkModeDetector() {
    when (Platform.Current) {
        Platform.MacOS -> MacOSThemeDetector.isDark()
        Platform.Windows -> WindowsThemeDetector.isDark()
        Platform.Linux -> LinuxPortalThemeDetector.isDark()
        else -> Unit
    }
}

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
