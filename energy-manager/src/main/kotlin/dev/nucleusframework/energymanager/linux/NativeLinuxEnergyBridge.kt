package dev.nucleusframework.energymanager.linux

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_energy_manager"

internal object NativeLinuxEnergyBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeLinuxEnergyBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeIsSupported(): Boolean

    @JvmStatic
    external fun nativeEnableEfficiencyMode(): Int

    @JvmStatic
    external fun nativeDisableEfficiencyMode(): Int

    @JvmStatic
    external fun nativeEnableLightEfficiencyMode(): Int

    @JvmStatic
    external fun nativeDisableLightEfficiencyMode(): Int

    @JvmStatic
    external fun nativeEnableThreadEfficiencyMode(): Int

    @JvmStatic
    external fun nativeDisableThreadEfficiencyMode(): Int

    /** @param mode 0 = system + display, 1 = system only. */
    @JvmStatic
    external fun nativeKeepAwake(mode: Int): Int

    @JvmStatic
    external fun nativeReleaseAwake(): Int

    @JvmStatic
    external fun nativeIsAwakeActive(): Boolean

    /**
     * Returns the inhibitor backend currently holding the awake request
     * (0 = none, 1 = GNOME SessionManager, 2 = systemd-logind, 3 = X11,
     * 4 = freedesktop PowerManagement). Exposed for verification.
     */
    @JvmStatic
    external fun nativeQueryAwakeBackend(): Int
}
