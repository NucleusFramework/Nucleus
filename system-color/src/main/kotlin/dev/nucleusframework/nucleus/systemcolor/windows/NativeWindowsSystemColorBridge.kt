package dev.nucleusframework.nucleus.systemcolor.windows

import androidx.compose.ui.graphics.Color
import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader
import dev.nucleusframework.nucleus.systemcolor.debugln
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

private const val TAG = "NativeWindowsSystemColorBridge"
private const val RGB_COMPONENTS = 3
private const val COLOR_MAX = 255f
private const val LIBRARY_NAME = "nucleus_systemcolor"

@Suppress("TooManyFunctions")
internal object NativeWindowsSystemColorBridge {
    private val accentListeners: MutableSet<Consumer<Color>> = ConcurrentHashMap.newKeySet()
    private val contrastListeners: MutableSet<Consumer<Boolean>> = ConcurrentHashMap.newKeySet()
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeWindowsSystemColorBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeGetAccentColor(out: IntArray): Boolean

    @JvmStatic
    external fun nativeIsHighContrast(): Boolean

    @JvmStatic
    external fun nativeIsAccentColorSupported(): Boolean

    @JvmStatic
    external fun nativeStartObserving()

    @JvmStatic
    external fun nativeStopObserving()

    fun getAccentColor(): Color? {
        if (!loaded) {
            debugln(TAG) { "Native library not loaded, cannot get accent color" }
            return null
        }
        val rgb = IntArray(RGB_COMPONENTS)
        val success = nativeGetAccentColor(rgb)
        if (!success) {
            debugln(TAG) { "nativeGetAccentColor returned false" }
            return null
        }
        debugln(TAG) { "Accent color: r=${rgb[0]}, g=${rgb[1]}, b=${rgb[2]}" }
        return Color(rgb[0] / COLOR_MAX, rgb[1] / COLOR_MAX, rgb[2] / COLOR_MAX)
    }

    @JvmStatic
    fun onAccentColorChanged(
        r: Int,
        g: Int,
        b: Int,
    ) {
        val color = Color(r / COLOR_MAX, g / COLOR_MAX, b / COLOR_MAX)
        debugln(TAG) { "Accent color changed: $color" }
        accentListeners.forEach { it.accept(color) }
    }

    @JvmStatic
    fun onHighContrastChanged(isHigh: Boolean) {
        debugln(TAG) { "High contrast mode changed: $isHigh" }
        contrastListeners.forEach { it.accept(isHigh) }
    }

    fun registerAccentListener(listener: Consumer<Color>) {
        accentListeners.add(listener)
    }

    fun removeAccentListener(listener: Consumer<Color>) {
        accentListeners.remove(listener)
    }

    fun registerContrastListener(listener: Consumer<Boolean>) {
        contrastListeners.add(listener)
    }

    fun removeContrastListener(listener: Consumer<Boolean>) {
        contrastListeners.remove(listener)
    }
}
