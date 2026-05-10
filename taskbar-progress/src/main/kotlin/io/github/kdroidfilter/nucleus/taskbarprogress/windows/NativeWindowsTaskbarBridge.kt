package io.github.kdroidfilter.nucleus.taskbarprogress.windows

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader
import java.awt.Window

private const val LIBRARY_NAME = "nucleus_taskbar_progress"

internal object NativeWindowsTaskbarBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeWindowsTaskbarBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeSetProgress(
        window: Window,
        completed: Long,
        total: Long,
    ): Int

    @JvmStatic
    external fun nativeSetProgressState(
        window: Window,
        flags: Int,
    ): Int

    @JvmStatic
    external fun nativeRequestAttention(
        window: Window,
        type: Int,
    ): Int

    @JvmStatic
    external fun nativeSetProgressByHwnd(
        hwnd: Long,
        completed: Long,
        total: Long,
    ): Int

    @JvmStatic
    external fun nativeSetProgressStateByHwnd(
        hwnd: Long,
        flags: Int,
    ): Int

    @JvmStatic
    external fun nativeRequestAttentionByHwnd(
        hwnd: Long,
        type: Int,
    ): Int
}
