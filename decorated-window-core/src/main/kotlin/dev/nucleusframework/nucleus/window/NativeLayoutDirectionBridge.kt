package dev.nucleusframework.nucleus.window

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

private const val LIBRARY_NAME = "nucleus_layout_direction"

internal object NativeLayoutDirectionBridge {
    private val buttonLayoutListeners: MutableSet<Consumer<String>> = ConcurrentHashMap.newKeySet()

    init {
        NativeLibraryLoader.load(LIBRARY_NAME, NativeLayoutDirectionBridge::class.java)
    }

    @JvmStatic
    external fun nativeIsRTL(): Boolean

    /**
     * Returns the GNOME `button-layout` GSettings value
     * (e.g. `"appmenu:close"` or `"close,minimize,maximize:"`),
     * or `null` if the schema is unavailable (non-GNOME desktop).
     */
    @JvmStatic
    external fun nativeGetButtonLayout(): String?

    @JvmStatic
    external fun nativeStartButtonLayoutObserving()

    @JvmStatic
    external fun nativeStopButtonLayoutObserving()

    /**
     * Called from native code when the button-layout GSettings value changes.
     */
    @JvmStatic
    fun onButtonLayoutChanged(layout: String) {
        buttonLayoutListeners.forEach { it.accept(layout) }
    }

    fun registerButtonLayoutListener(listener: Consumer<String>) {
        buttonLayoutListeners.add(listener)
    }

    fun removeButtonLayoutListener(listener: Consumer<String>) {
        buttonLayoutListeners.remove(listener)
    }
}
