package dev.nucleusframework.nucleus.systemcolor.linux

import androidx.compose.ui.graphics.Color
import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader
import dev.nucleusframework.nucleus.systemcolor.debugln
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

private const val TAG = "NativeLinuxSystemColorBridge"
private const val RGB_COMPONENTS = 3
private const val LIBRARY_NAME = "nucleus_systemcolor"

@Suppress("TooManyFunctions")
internal object NativeLinuxSystemColorBridge {
    private val accentListeners: MutableSet<Consumer<Color>> = ConcurrentHashMap.newKeySet()
    private val contrastListeners: MutableSet<Consumer<Boolean>> = ConcurrentHashMap.newKeySet()
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeLinuxSystemColorBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeGetAccentColor(out: FloatArray): Boolean

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
        val rgb = FloatArray(RGB_COMPONENTS)
        val success = nativeGetAccentColor(rgb)
        if (!success) {
            debugln(TAG) { "nativeGetAccentColor returned false" }
            return null
        }
        debugln(TAG) { "Accent color: r=${rgb[0]}, g=${rgb[1]}, b=${rgb[2]}" }
        return Color(rgb[0], rgb[1], rgb[2])
    }

    @JvmStatic
    fun onAccentColorChanged(
        r: Float,
        g: Float,
        b: Float,
    ) {
        val color = Color(r, g, b)
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
