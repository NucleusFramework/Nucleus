package dev.nucleusframework.nucleus.darkmodedetector.linux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.nucleusframework.nucleus.darkmodedetector.debugln
import java.util.function.Consumer

private const val TAG = "LinuxPortalThemeDetector"

/**
 * LinuxPortalThemeDetector uses a JNI native library to read the XDG Desktop Portal
 * color-scheme preference via the org.freedesktop.portal.Settings D-Bus interface.
 *
 * color-scheme value 1 means prefer-dark; all other values mean light/no preference.
 *
 * The detector also monitors for SettingChanged signals in real-time via a background
 * D-Bus dispatch thread.
 */
internal object LinuxPortalThemeDetector {
    init {
        debugln(TAG) { "Initializing Linux portal theme observer via JNI" }
        NativeLinuxBridge.nativeStartObserving()
    }

    fun isDark(): Boolean = NativeLinuxBridge.nativeIsDark()

    fun registerListener(listener: Consumer<Boolean>) {
        NativeLinuxBridge.registerListener(listener)
    }

    fun removeListener(listener: Consumer<Boolean>) {
        NativeLinuxBridge.removeListener(listener)
    }
}

/**
 * A helper composable function that returns the current Linux dark mode state
 * via the XDG Desktop Portal, updating automatically when the system theme changes.
 */
@Composable
fun isLinuxInDarkMode(): Boolean {
    val darkModeState = remember { mutableStateOf(LinuxPortalThemeDetector.isDark()) }
    DisposableEffect(Unit) {
        debugln(TAG) { "Registering Linux portal dark mode listener in Compose" }
        val listener =
            Consumer<Boolean> { newValue ->
                debugln(TAG) { "Compose Linux portal dark mode updated: $newValue" }
                darkModeState.value = newValue
            }
        LinuxPortalThemeDetector.registerListener(listener)
        onDispose {
            debugln(TAG) { "Removing Linux portal dark mode listener in Compose" }
            LinuxPortalThemeDetector.removeListener(listener)
        }
    }
    return darkModeState.value
}
