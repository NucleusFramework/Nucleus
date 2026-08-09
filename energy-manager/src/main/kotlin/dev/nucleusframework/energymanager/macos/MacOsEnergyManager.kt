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
     * [AwakeMode.SYSTEM_AND_DISPLAY] takes a kIOPMAssertPreventUserIdleDisplaySleep
     * assertion, [AwakeMode.SYSTEM_ONLY] a kIOPMAssertPreventUserIdleSystemSleep one
     * (what `caffeinate -i` holds). Both only inhibit *idle* sleep: closing the lid
     * or choosing Sleep from the Apple menu still puts the machine to sleep.
     */
    @Synchronized
    override fun keepAwake(mode: AwakeMode) = callNative { NativeMacOsEnergyBridge.nativeKeepAwake(mode.nativeCode) }

    @Synchronized
    override fun releaseAwake() = callNative { NativeMacOsEnergyBridge.nativeReleaseAwake() }

    override fun isAwakeActive(): Boolean =
        NativeMacOsEnergyBridge.isLoaded &&
            runCatching { NativeMacOsEnergyBridge.nativeIsAwakeActive() }.getOrDefault(false)

    /** Mirrors the AWAKE_* constants in the native bridge. */
    private val AwakeMode.nativeCode: Int
        get() =
            when (this) {
                AwakeMode.SYSTEM_AND_DISPLAY -> 0
                AwakeMode.SYSTEM_ONLY -> 1
            }
}
