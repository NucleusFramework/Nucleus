package dev.nucleusframework.nucleus.window.utils.linux

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_linux_jni"

internal object JniLinuxWindowBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, JniLinuxWindowBridge::class.java)

    val isLoaded: Boolean get() = loaded

    // Initiates a native window move via _NET_WM_MOVERESIZE.
    // rootX/rootY: absolute mouse coordinates on screen.
    // button: X11 button number (1 = left).
    // Returns true on success.
    @JvmStatic
    external fun nativeStartWindowMove(
        awtWindow: java.awt.Window,
        rootX: Int,
        rootY: Int,
        button: Int,
    ): Boolean

    // Checks if the window manager supports _NET_WM_MOVERESIZE.
    @JvmStatic
    external fun nativeIsWmMoveResizeSupported(awtWindow: java.awt.Window): Boolean

    // Toggles native fullscreen via _NET_WM_STATE_FULLSCREEN.
    @JvmStatic
    external fun nativeSetFullscreen(
        awtWindow: java.awt.Window,
        fullscreen: Boolean,
    ): Boolean

    // Checks if the window currently has _NET_WM_STATE_FULLSCREEN set.
    @JvmStatic
    external fun nativeIsFullscreen(awtWindow: java.awt.Window): Boolean
}
