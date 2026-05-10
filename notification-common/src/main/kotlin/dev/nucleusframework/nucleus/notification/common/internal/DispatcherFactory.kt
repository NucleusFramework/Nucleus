package dev.nucleusframework.nucleus.notification.common.internal

import java.util.Locale

internal object DispatcherFactory {
    fun create(): PlatformDispatcher? {
        val platform = detectPlatform() ?: return null
        return when {
            platform.contains("mac") || platform.contains("darwin") ->
                MacOsDispatcher.createIfAvailable()
            platform.contains("win") ->
                WindowsDispatcher.createIfAvailable()
            platform.contains("nux") || platform.contains("nix") || platform.contains("aix") ->
                LinuxDispatcher.createIfAvailable()
            else -> null
        }
    }

    private fun detectPlatform(): String? =
        try {
            System.getProperty("os.name", "unknown").lowercase(Locale.ENGLISH)
        } catch (_: SecurityException) {
            null
        }
}
