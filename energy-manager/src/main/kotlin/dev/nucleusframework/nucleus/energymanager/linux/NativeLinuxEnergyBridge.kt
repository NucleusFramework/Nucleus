package dev.nucleusframework.nucleus.energymanager.linux

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

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

    @JvmStatic
    external fun nativeKeepScreenAwake(): Int

    @JvmStatic
    external fun nativeReleaseScreenAwake(): Int

    @JvmStatic
    external fun nativeIsScreenAwakeActive(): Boolean
}
