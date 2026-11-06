package dev.nucleusframework.hidpi

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_linux_hidpi_jni"

/** JNI bridge for what a Linux process sets up at startup: HiDPI scale detection and the malloc arena cap. */
internal object LinuxStartupBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, LinuxStartupBridge::class.java)

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

    // Caps glibc's malloc arenas with mallopt(M_ARENA_MAX, max).
    // Returns 1 when applied, 0 when glibc refused it, -1 when the libc is not glibc.
    @JvmStatic
    external fun nativeSetMallocArenaMax(max: Int): Int
}
