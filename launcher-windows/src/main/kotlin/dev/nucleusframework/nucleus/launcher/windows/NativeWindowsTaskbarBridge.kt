package dev.nucleusframework.nucleus.launcher.windows

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader
import java.awt.Window

private const val LIBRARY_NAME = "nucleus_launcher_windows"

internal object NativeWindowsTaskbarBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeWindowsTaskbarBridge::class.java)

    val isLoaded: Boolean get() = loaded

    // ---- HWND extraction ----

    @JvmStatic
    external fun nativeGetHwnd(window: Window): Long

    // ---- Overlay Icon ----

    @JvmStatic
    external fun nativeSetOverlayIcon(
        window: Window,
        iconType: Int,
        iconPath: String,
        iconIndex: Int,
        description: String,
    ): String?

    @JvmStatic
    external fun nativeClearOverlayIcon(window: Window): String?

    // ---- Thumbnail Toolbar ----

    @JvmStatic
    external fun nativeThumbBarSetButtons(
        window: Window,
        ids: IntArray,
        tooltips: Array<String>,
        flags: IntArray,
        iconTypes: IntArray,
        iconPaths: Array<String>,
        iconIndices: IntArray,
        callback: Any?,
    ): String?

    @JvmStatic
    external fun nativeThumbBarUpdateButtons(
        window: Window,
        ids: IntArray,
        tooltips: Array<String>,
        flags: IntArray,
        iconTypes: IntArray,
        iconPaths: Array<String>,
        iconIndices: IntArray,
    ): String?

    @JvmStatic
    external fun nativeThumbBarUnregister(window: Window): String?

    @JvmStatic
    external fun nativeThumbBarUnregisterByHwnd(hwnd: Long): String?
}
