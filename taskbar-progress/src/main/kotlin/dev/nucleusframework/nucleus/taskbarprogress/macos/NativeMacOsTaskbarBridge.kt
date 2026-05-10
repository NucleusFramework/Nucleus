package dev.nucleusframework.nucleus.taskbarprogress.macos

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_taskbar_progress"

internal object NativeMacOsTaskbarBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeMacOsTaskbarBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeSetDockProgress(
        completed: Long,
        total: Long,
    ): Int

    @JvmStatic
    external fun nativeSetDockState(flags: Int): Int

    @JvmStatic
    external fun nativeRequestAttention(type: Int): Int

    @JvmStatic
    external fun nativeCancelAttention(requestId: Int)
}
