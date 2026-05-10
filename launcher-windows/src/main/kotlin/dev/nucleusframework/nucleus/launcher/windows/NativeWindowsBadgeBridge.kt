package dev.nucleusframework.nucleus.launcher.windows

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_launcher_windows"

internal object NativeWindowsBadgeBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeWindowsBadgeBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeInitialize(
        aumid: String,
        isAppx: Boolean,
    ): String?

    @JvmStatic
    external fun nativeSetBadgeNumber(value: Int): String?

    @JvmStatic
    external fun nativeSetBadgeGlyph(glyph: String): String?

    @JvmStatic
    external fun nativeClearBadge(): String?

    @JvmStatic
    external fun nativeUninitialize()
}
