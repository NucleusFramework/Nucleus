package dev.nucleusframework.nucleus.systemcolor.windows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.nucleus.systemcolor.debugln
import java.util.function.Consumer

private const val TAG = "WindowsSystemColor"

internal object WindowsSystemColorDetector {
    init {
        debugln(TAG) { "Initializing Windows system color observer via JNI" }
        NativeWindowsSystemColorBridge.nativeStartObserving()
    }

    fun isAccentColorSupported(): Boolean = NativeWindowsSystemColorBridge.nativeIsAccentColorSupported()

    fun getAccentColor(): Color? = NativeWindowsSystemColorBridge.getAccentColor()

    fun isHighContrast(): Boolean = NativeWindowsSystemColorBridge.nativeIsHighContrast()

    fun registerAccentListener(listener: Consumer<Color>) {
        NativeWindowsSystemColorBridge.registerAccentListener(listener)
    }

    fun removeAccentListener(listener: Consumer<Color>) {
        NativeWindowsSystemColorBridge.removeAccentListener(listener)
    }

    fun registerContrastListener(listener: Consumer<Boolean>) {
        NativeWindowsSystemColorBridge.registerContrastListener(listener)
    }

    fun removeContrastListener(listener: Consumer<Boolean>) {
        NativeWindowsSystemColorBridge.removeContrastListener(listener)
    }
}

@Composable
internal fun windowsAccentColor(): Color? {
    val colorState =
        remember { mutableStateOf(WindowsSystemColorDetector.getAccentColor()) }
    DisposableEffect(Unit) {
        val listener =
            Consumer<Color> { newColor ->
                colorState.value = newColor
            }
        WindowsSystemColorDetector.registerAccentListener(listener)
        onDispose {
            WindowsSystemColorDetector.removeAccentListener(listener)
        }
    }
    return colorState.value
}

@Composable
internal fun windowsHighContrast(): Boolean {
    val contrastState =
        remember { mutableStateOf(WindowsSystemColorDetector.isHighContrast()) }
    DisposableEffect(Unit) {
        val listener =
            Consumer<Boolean> { isHigh ->
                contrastState.value = isHigh
            }
        WindowsSystemColorDetector.registerContrastListener(listener)
        onDispose {
            WindowsSystemColorDetector.removeContrastListener(listener)
        }
    }
    return contrastState.value
}
