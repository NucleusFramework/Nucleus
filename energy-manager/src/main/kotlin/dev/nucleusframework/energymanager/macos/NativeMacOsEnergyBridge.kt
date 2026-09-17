package dev.nucleusframework.energymanager.macos

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_energy_manager"

internal object NativeMacOsEnergyBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeMacOsEnergyBridge::class.java)

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
     * Returns the mode of the IOKit assertion this process currently holds
     * (0 = system + display, 1 = system only, -1 = none), read back from the
     * assertion's own properties. Exposed for verification.
     */
    @JvmStatic
    external fun nativeQueryAwakeMode(): Int
}
