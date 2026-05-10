package dev.nucleusframework.nucleus.hidpi

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_linux_hidpi_jni"

internal object HiDpiLinuxBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, HiDpiLinuxBridge::class.java)

    val isLoaded: Boolean get() = loaded

    // Returns the native HiDPI scale factor detected from the Linux desktop
    // environment (GSettings, GDK_SCALE, Xft.dpi, …).
    // Returns 0.0 if the scale cannot be determined.
    @JvmStatic
    external fun nativeGetScaleFactor(): Double

    // Sets GDK_SCALE in the process environment so the JDK's native
    // X11GraphicsDevice.getNativeScaleFactor() picks up the scale through
    // the standard detection path. This ensures both rendering AND mouse
    // event coordinates are properly scaled (XWindow.scaleDown).
    // Does not overwrite GDK_SCALE if it is already set by the desktop session.
    @JvmStatic
    external fun nativeApplyScaleToEnv(scale: Int)
}
