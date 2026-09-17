package dev.nucleusframework.energymanager.windows

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_energy_manager"

internal object NativeWindowsEnergyBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeWindowsEnergyBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeIsSupported(): Boolean

    @JvmStatic
    external fun nativeEnableEfficiencyMode(): Int

    @JvmStatic
    external fun nativeDisableEfficiencyMode(): Int

    @JvmStatic
    external fun nativeEnableThreadEfficiencyMode(): Int

    @JvmStatic
    external fun nativeDisableThreadEfficiencyMode(): Int

    @JvmStatic
    external fun nativeEnableLightEfficiencyMode(): Int

    @JvmStatic
    external fun nativeDisableLightEfficiencyMode(): Int

    /** @param mode 0 = system + display, 1 = system only. */
    @JvmStatic
    external fun nativeKeepAwake(mode: Int): Int

    @JvmStatic
    external fun nativeReleaseAwake(): Int

    @JvmStatic
    external fun nativeIsAwakeActive(): Boolean

    /**
     * Returns the EXECUTION_STATE flags Windows currently holds for the calling
     * thread (ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED), or 0 if
     * the query failed. Exposed for verification — the state is per-thread.
     */
    @JvmStatic
    external fun nativeQueryAwakeFlags(): Int
}
