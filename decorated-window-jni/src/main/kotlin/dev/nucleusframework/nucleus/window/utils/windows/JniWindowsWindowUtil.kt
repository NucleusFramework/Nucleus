package dev.nucleusframework.nucleus.window.utils.windows

import java.awt.Window

internal object JniWindowsWindowUtil {
    // Extracts the native HWND from an AWT Window.
    // Delegates to native JNI code which bypasses JPMS module restrictions.
    // Returns 0 if the handle cannot be obtained (e.g. peer not yet created).
    fun getHwnd(w: Window?): Long {
        if (w == null || !JniWindowsDecorationBridge.isLoaded) return 0L
        return JniWindowsDecorationBridge.nativeGetHwnd(w)
    }
}
