package dev.nucleusframework.tray.internal.macos

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.function.Consumer

internal object MacOSMenuBarThemeDetector {
    private val listeners: MutableSet<Consumer<Boolean>> = ConcurrentHashMap.newKeySet()

    private val callbackExecutor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "MacOS MenuBar Theme Detector Thread").apply { isDaemon = true }
        }

    private class ThemeCallback : MacNativeBridge.ThemeChangeCallback {
        override fun onThemeChanged(isDark: Int) {
            callbackExecutor.execute {
                val dark = isDark != 0
                notifyListeners(dark)
            }
        }
    }

    private val themeChangedCallback = ThemeCallback()

    init {
        MacNativeBridge.nativeSetThemeCallback(themeChangedCallback)
    }

    fun isDark(): Boolean = MacNativeBridge.nativeIsMenuDark() != 0

    fun registerListener(listener: Consumer<Boolean>) {
        listeners.add(listener)
        // Notify with current state upon registration
        listener.accept(isDark())
    }

    fun removeListener(listener: Consumer<Boolean>) {
        listeners.remove(listener)
    }

    private fun notifyListeners(isDark: Boolean) {
        listeners.forEach { it.accept(isDark) }
    }
}
