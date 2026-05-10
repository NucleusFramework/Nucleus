package dev.nucleusframework.nucleus.darkmodedetector.mac

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.nucleusframework.nucleus.darkmodedetector.debugln
import java.util.function.Consumer

private const val TAG = "MacOSThemeDetector"

/**
 * MacOSThemeDetector registers a native observer via JNI with NSDistributedNotificationCenter
 * to detect theme changes in macOS. It reads the system preference "AppleInterfaceStyle"
 * (which is "Dark" when in dark mode) from NSUserDefaults.
 */
internal object MacOSThemeDetector {
    init {
        debugln(TAG) { "Initializing macOS theme observer via JNI" }
        NativeDarkModeBridge.nativeStartObserving()
    }

    fun isDark(): Boolean = NativeDarkModeBridge.nativeIsDark()

    fun registerListener(listener: Consumer<Boolean>) {
        NativeDarkModeBridge.registerListener(listener)
    }

    fun removeListener(listener: Consumer<Boolean>) {
        NativeDarkModeBridge.removeListener(listener)
    }
}

/**
 * A helper composable function that returns the current macOS dark mode state,
 * updating automatically when the system theme changes.
 */
@Composable
internal fun isMacOsInDarkMode(): Boolean {
    val darkModeState = remember { mutableStateOf(MacOSThemeDetector.isDark()) }
    DisposableEffect(Unit) {
        debugln(TAG) { "Registering macOS dark mode listener in Compose" }
        val listener =
            Consumer<Boolean> { newValue ->
                debugln(TAG) { "Compose macOS dark mode updated: $newValue" }
                darkModeState.value = newValue
            }
        MacOSThemeDetector.registerListener(listener)
        onDispose {
            debugln(TAG) { "Removing macOS dark mode listener in Compose" }
            MacOSThemeDetector.removeListener(listener)
        }
    }
    return darkModeState.value
}
