package dev.nucleusframework.nucleus.darkmodedetector.mac

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader
import dev.nucleusframework.nucleus.darkmodedetector.debugln
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

private const val TAG = "NativeDarkModeBridge"
private const val LIBRARY_NAME = "nucleus_darkmode"

internal object NativeDarkModeBridge {
    private val listeners: MutableSet<Consumer<Boolean>> = ConcurrentHashMap.newKeySet()
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeDarkModeBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeIsDark(): Boolean

    @JvmStatic
    external fun nativeStartObserving()

    @JvmStatic
    external fun nativeStopObserving()

    @JvmStatic
    fun onThemeChanged(isDark: Boolean) {
        debugln(TAG) { "Theme change detected via JNI. Dark mode: $isDark" }
        listeners.forEach { it.accept(isDark) }
    }

    fun registerListener(listener: Consumer<Boolean>) {
        listeners.add(listener)
    }

    fun removeListener(listener: Consumer<Boolean>) {
        listeners.remove(listener)
    }
}
