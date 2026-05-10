package dev.nucleusframework.nucleus.darkmodedetector.windows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.nucleusframework.nucleus.darkmodedetector.debugln
import java.util.function.Consumer

private const val TAG = "WindowsThemeDetector"

/**
 * WindowsThemeDetector uses a JNI native library to read the Windows registry value:
 * HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme
 *
 * If this value = 0 => Dark mode. If this value = 1 => Light mode.
 *
 * The detector monitors the registry for changes in real-time via a native
 * background thread using RegNotifyChangeKeyValue in async mode.
 */
internal object WindowsThemeDetector {
    init {
        debugln(TAG) { "Initializing Windows theme observer via JNI" }
        NativeWindowsBridge.nativeStartObserving()
    }

    fun isDark(): Boolean = NativeWindowsBridge.nativeIsDark()

    fun registerListener(listener: Consumer<Boolean>) {
        NativeWindowsBridge.registerListener(listener)
    }

    fun removeListener(listener: Consumer<Boolean>) {
        NativeWindowsBridge.removeListener(listener)
    }
}

/**
 * Composable function that returns whether Windows is currently in dark mode.
 */
@Composable
internal fun isWindowsInDarkMode(): Boolean {
    val darkModeState = remember { mutableStateOf(WindowsThemeDetector.isDark()) }

    DisposableEffect(Unit) {
        debugln(TAG) { "Registering Windows dark mode listener in Compose" }
        val listener =
            Consumer<Boolean> { newValue ->
                debugln(TAG) { "Windows dark mode updated: $newValue" }
                darkModeState.value = newValue
            }

        WindowsThemeDetector.registerListener(listener)

        onDispose {
            debugln(TAG) { "Removing Windows dark mode listener in Compose" }
            WindowsThemeDetector.removeListener(listener)
        }
    }

    return darkModeState.value
}
