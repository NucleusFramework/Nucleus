package dev.nucleusframework.nucleus.energymanager.windows

import dev.nucleusframework.nucleus.energymanager.EnergyManager
import dev.nucleusframework.nucleus.energymanager.PlatformEnergyManager

internal object WindowsEnergyManager : PlatformEnergyManager {
    private fun callNative(block: () -> Int): EnergyManager.Result {
        if (!NativeWindowsEnergyBridge.isLoaded) {
            return EnergyManager.Result(false, -1, "Native library not loaded")
        }
        return try {
            val rc = block()
            if (rc == 0) {
                EnergyManager.Result(true)
            } else {
                EnergyManager.Result(false, rc, "Native call failed with error code $rc")
            }
        } catch (e: UnsatisfiedLinkError) {
            EnergyManager.Result(false, -1, "Exception: ${e.message}")
        }
    }

    override fun isAvailable(): Boolean =
        NativeWindowsEnergyBridge.isLoaded &&
            runCatching { NativeWindowsEnergyBridge.nativeIsSupported() }.getOrDefault(false)

    override fun enableEfficiencyMode() = callNative { NativeWindowsEnergyBridge.nativeEnableEfficiencyMode() }

    override fun enableLightEfficiencyMode(): EnergyManager.Result =
        callNative { NativeWindowsEnergyBridge.nativeEnableLightEfficiencyMode() }

    override fun disableLightEfficiencyMode(): EnergyManager.Result =
        callNative { NativeWindowsEnergyBridge.nativeDisableLightEfficiencyMode() }

    override fun disableEfficiencyMode() = callNative { NativeWindowsEnergyBridge.nativeDisableEfficiencyMode() }

    override fun enableThreadEfficiencyMode(): EnergyManager.Result =
        callNative { NativeWindowsEnergyBridge.nativeEnableThreadEfficiencyMode() }

    override fun disableThreadEfficiencyMode(): EnergyManager.Result =
        callNative { NativeWindowsEnergyBridge.nativeDisableThreadEfficiencyMode() }

    override fun keepScreenAwake() = callNative { NativeWindowsEnergyBridge.nativeKeepScreenAwake() }

    override fun releaseScreenAwake() = callNative { NativeWindowsEnergyBridge.nativeReleaseScreenAwake() }

    override fun isScreenAwakeActive(): Boolean =
        NativeWindowsEnergyBridge.isLoaded &&
            runCatching { NativeWindowsEnergyBridge.nativeIsScreenAwakeActive() }.getOrDefault(false)
}
