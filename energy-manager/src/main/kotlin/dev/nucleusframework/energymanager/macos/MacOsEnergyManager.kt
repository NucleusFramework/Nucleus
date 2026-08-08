package dev.nucleusframework.energymanager.macos

import dev.nucleusframework.energymanager.AwakeMode
import dev.nucleusframework.energymanager.EnergyManager
import dev.nucleusframework.energymanager.PlatformEnergyManager

internal object MacOsEnergyManager : PlatformEnergyManager {
    private fun callNative(block: () -> Int): EnergyManager.Result {
        if (!NativeMacOsEnergyBridge.isLoaded) {
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
        NativeMacOsEnergyBridge.isLoaded &&
            runCatching { NativeMacOsEnergyBridge.nativeIsSupported() }.getOrDefault(false)

    override fun enableEfficiencyMode() = callNative { NativeMacOsEnergyBridge.nativeEnableEfficiencyMode() }

    override fun disableEfficiencyMode() = callNative { NativeMacOsEnergyBridge.nativeDisableEfficiencyMode() }

    override fun enableLightEfficiencyMode(): EnergyManager.Result =
        callNative { NativeMacOsEnergyBridge.nativeEnableLightEfficiencyMode() }

    override fun disableLightEfficiencyMode(): EnergyManager.Result =
        callNative { NativeMacOsEnergyBridge.nativeDisableLightEfficiencyMode() }

    override fun enableThreadEfficiencyMode(): EnergyManager.Result =
        callNative { NativeMacOsEnergyBridge.nativeEnableThreadEfficiencyMode() }

    override fun disableThreadEfficiencyMode(): EnergyManager.Result =
        callNative { NativeMacOsEnergyBridge.nativeDisableThreadEfficiencyMode() }

    /**
     * [AwakeMode.SYSTEM_ONLY] would map to kIOPMAssertPreventUserIdleSystemSleep,
     * but that path is not implemented yet — the request is rejected rather than
     * silently keeping the display on as well.
     */
    override fun keepAwake(mode: AwakeMode) =
        if (mode == AwakeMode.SYSTEM_ONLY) {
            EnergyManager.Result(false, -1, "AwakeMode.SYSTEM_ONLY is not implemented on macOS yet")
        } else {
            callNative { NativeMacOsEnergyBridge.nativeKeepScreenAwake() }
        }

    override fun releaseAwake() = callNative { NativeMacOsEnergyBridge.nativeReleaseScreenAwake() }

    override fun isAwakeActive(): Boolean =
        NativeMacOsEnergyBridge.isLoaded &&
            runCatching { NativeMacOsEnergyBridge.nativeIsScreenAwakeActive() }.getOrDefault(false)
}
