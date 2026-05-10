package dev.nucleusframework.nucleus.autolaunch.linux

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_autolaunch_linux"

/**
 * JNI bridge for Linux auto-launch.
 *
 * Exposes two mechanisms:
 *  - **systemd user service** (host deb/rpm/AppImage) — [writeUnitFile], [enableUnit],
 *    [disableUnit], [getUnitFileState] via `org.freedesktop.systemd1`.
 *  - **XDG Desktop Portal Background** (Flatpak sandbox, where the sandbox cannot
 *    talk to the host's systemd) — [requestBackground].
 *
 * All methods return status codes instead of throwing; the Kotlin side maps them
 * to [dev.nucleusframework.nucleus.autolaunch.AutoLaunchState] /
 * [dev.nucleusframework.nucleus.autolaunch.AutoLaunchResult].
 */
@Suppress("TooManyFunctions")
internal object NativeAutoLaunchLinuxBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeAutoLaunchLinuxBridge::class.java)

    val isLoaded: Boolean get() = loaded

    // ---- Portal Background (Flatpak) ----------------------------------

    /** Returns `true` if `org.freedesktop.portal.Desktop` owns a name on the session bus. */
    fun isPortalAvailable(): Boolean = if (loaded) nativeIsPortalAvailable() else false

    @JvmStatic
    private external fun nativeIsPortalAvailable(): Boolean

    /**
     * Calls `org.freedesktop.portal.Background.RequestBackground` with `autostart=[enable]`
     * and waits (up to 60s) for the Response signal.
     *
     * Return codes:
     *  -  0 = success
     *  - -1 = error (D-Bus / portal internal)
     *  - -2 = cancelled by user
     *  - -3 = portal not available
     */
    fun requestBackground(
        enable: Boolean,
        commandline: Array<String>,
        reason: String,
    ): Int = if (loaded) nativeRequestBackground(enable, commandline, reason) else RC_NO_PORTAL

    @JvmStatic
    private external fun nativeRequestBackground(
        enable: Boolean,
        commandline: Array<String>,
        reason: String,
    ): Int

    // ---- systemd --user (host) ----------------------------------------

    /** Writes `$XDG_CONFIG_HOME/systemd/user/<unitName>` with [content]. Returns 0 on success. */
    fun writeUnitFile(
        unitName: String,
        content: String,
    ): Int = if (loaded) nativeWriteUnitFile(unitName, content) else RC_ERROR

    @JvmStatic
    private external fun nativeWriteUnitFile(
        unitName: String,
        content: String,
    ): Int

    /** Deletes the user unit file. Returns 0 if deleted or absent. */
    fun deleteUnitFile(unitName: String): Int = if (loaded) nativeDeleteUnitFile(unitName) else RC_ERROR

    @JvmStatic
    private external fun nativeDeleteUnitFile(unitName: String): Int

    /** Calls `Manager.EnableUnitFiles` (persistent, force=true) + `Reload`. */
    fun enableUnit(unitName: String): Int = if (loaded) nativeEnableUnit(unitName) else RC_ERROR

    @JvmStatic
    private external fun nativeEnableUnit(unitName: String): Int

    /** Calls `Manager.DisableUnitFiles` (persistent) + `Reload`. */
    fun disableUnit(unitName: String): Int = if (loaded) nativeDisableUnit(unitName) else RC_ERROR

    @JvmStatic
    private external fun nativeDisableUnit(unitName: String): Int

    /**
     * Calls `Manager.GetUnitFileState`. Returns:
     *  -  0 = disabled / linked / static / masked
     *  -  1 = enabled (persistent)
     *  -  2 = enabled-runtime (transient)
     *  - -1 = D-Bus error
     *  - -2 = unit file not installed
     */
    fun getUnitFileState(unitName: String): Int = if (loaded) nativeGetUnitFileState(unitName) else RC_ERROR

    @JvmStatic
    private external fun nativeGetUnitFileState(unitName: String): Int

    // ---- Diagnostic ---------------------------------------------------

    /** Diagnostic log of native auto-launch operations, accumulated since process start. */
    fun getDiagnostic(): String = if (loaded) nativeGetDiagnostic() else "(native not loaded)"

    @JvmStatic
    private external fun nativeGetDiagnostic(): String

    // ---- Return code constants ----------------------------------------

    const val RC_OK: Int = 0
    const val RC_ERROR: Int = -1
    const val RC_USER_DENIED: Int = -2
    const val RC_NO_PORTAL: Int = -3

    const val RC_STATE_DISABLED: Int = 0
    const val RC_STATE_ENABLED: Int = 1
    const val RC_STATE_ENABLED_RUNTIME: Int = 2
    const val RC_STATE_NOT_INSTALLED: Int = -2
}
