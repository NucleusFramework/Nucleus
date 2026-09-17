package dev.nucleusframework.darkmodedetector

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import java.util.function.Consumer

/**
 * Composable function that returns whether the system is in dark mode.
 * It handles macOS, Windows, and Linux.
 */
@Composable
public fun isSystemInDarkMode(): Boolean {
    val isInPreview = LocalInspectionMode.current
    if (isInPreview) {
        return isSystemInDarkTheme()
    }
    val darkModeDetector = remember { getPlatformDarkModeDetector() }
    val darkModeState = remember { mutableStateOf(darkModeDetector.isDark()) }

    DisposableEffect(Unit) {
        debugln(TAG) { "Registering OS dark mode listener in Compose" }
        val listener =
            Consumer<Boolean> { newValue ->
                debugln(TAG) { "OS dark mode updated: $newValue" }
                darkModeState.value = newValue
            }

        darkModeDetector.registerListener(listener)

        onDispose {
            debugln(TAG) { "Removing OS dark mode listener in Compose" }
            darkModeDetector.removeListener(listener)
        }
    }

    return darkModeState.value
}

private const val TAG = "PlatformThemeDetectorCompose"
