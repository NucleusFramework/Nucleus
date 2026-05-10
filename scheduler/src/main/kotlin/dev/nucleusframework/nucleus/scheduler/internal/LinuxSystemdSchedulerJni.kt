package dev.nucleusframework.nucleus.scheduler.internal

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_scheduler_linux"

/**
 * JNI bridge to systemd user session management via D-Bus (GIO/GDBus).
 *
 * All methods are thin wrappers around D-Bus calls to
 * org.freedesktop.systemd1.Manager — no systemctl subprocess involved.
 */
internal object LinuxSystemdSchedulerJni {
    val isLoaded: Boolean = NativeLibraryLoader.load(LIBRARY_NAME, LinuxSystemdSchedulerJni::class.java)

    /** Equivalent to `systemctl --user daemon-reload`. */
    @JvmStatic
    external fun nativeReload(): Boolean

    /**
     * Enable unit files and optionally start them.
     * Returns null on success, error message on failure.
     */
    @JvmStatic
    external fun nativeEnableUnitFiles(
        unitFiles: Array<String>,
        startNow: Boolean,
    ): String?

    /**
     * Disable unit files and optionally stop them first.
     * Returns null on success, error message on failure.
     */
    @JvmStatic
    external fun nativeDisableUnitFiles(
        unitFiles: Array<String>,
        stopNow: Boolean,
    ): String?

    /** Start a unit. Returns true on success. */
    @JvmStatic
    external fun nativeStartUnit(unitName: String): Boolean

    /** Returns the UnitFileState ("enabled", "disabled", ...) or null on error. */
    @JvmStatic
    external fun nativeGetUnitFileState(unitName: String): String?

    /** Returns the ActiveState ("active", "inactive", ...) or null on error. */
    @JvmStatic
    external fun nativeGetUnitActiveState(unitName: String): String?

    /** Returns NextElapseUSecRealtime (microseconds since epoch) or 0 on error. */
    @JvmStatic
    external fun nativeGetTimerNextElapseUSec(timerName: String): Long
}
