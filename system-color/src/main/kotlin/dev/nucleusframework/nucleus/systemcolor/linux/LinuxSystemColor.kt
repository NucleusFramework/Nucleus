package dev.nucleusframework.nucleus.systemcolor.linux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.nucleus.systemcolor.debugln
import java.util.function.Consumer

private const val TAG = "LinuxSystemColorDetector"

internal object LinuxSystemColorDetector {
    init {
        debugln(TAG) { "Initializing Linux system color observer via JNI (D-Bus)" }
        NativeLinuxSystemColorBridge.nativeStartObserving()
    }

    fun isAccentColorSupported(): Boolean = NativeLinuxSystemColorBridge.nativeIsAccentColorSupported()

    fun getAccentColor(): Color? = NativeLinuxSystemColorBridge.getAccentColor()

    fun isHighContrast(): Boolean = NativeLinuxSystemColorBridge.nativeIsHighContrast()

    fun registerAccentListener(listener: Consumer<Color>) {
        NativeLinuxSystemColorBridge.registerAccentListener(listener)
    }

    fun removeAccentListener(listener: Consumer<Color>) {
        NativeLinuxSystemColorBridge.removeAccentListener(listener)
    }

    fun registerContrastListener(listener: Consumer<Boolean>) {
        NativeLinuxSystemColorBridge.registerContrastListener(listener)
    }

    fun removeContrastListener(listener: Consumer<Boolean>) {
        NativeLinuxSystemColorBridge.removeContrastListener(listener)
    }
}

internal fun isLinuxAccentColorSupported(): Boolean = LinuxSystemColorDetector.isAccentColorSupported()

@Composable
internal fun linuxAccentColor(): Color? {
    val colorState = remember { mutableStateOf(LinuxSystemColorDetector.getAccentColor()) }
    DisposableEffect(Unit) {
        val listener =
            Consumer<Color> { newColor ->
                colorState.value = newColor
            }
        LinuxSystemColorDetector.registerAccentListener(listener)
        onDispose {
            LinuxSystemColorDetector.removeAccentListener(listener)
        }
    }
    return colorState.value
}

@Composable
internal fun linuxHighContrast(): Boolean {
    val contrastState = remember { mutableStateOf(LinuxSystemColorDetector.isHighContrast()) }
    DisposableEffect(Unit) {
        val listener =
            Consumer<Boolean> { isHigh ->
                contrastState.value = isHigh
            }
        LinuxSystemColorDetector.registerContrastListener(listener)
        onDispose {
            LinuxSystemColorDetector.removeContrastListener(listener)
        }
    }
    return contrastState.value
}
