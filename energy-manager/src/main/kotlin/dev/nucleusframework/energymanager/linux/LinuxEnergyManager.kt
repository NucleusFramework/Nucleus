package dev.nucleusframework.energymanager.linux

import dev.nucleusframework.energymanager.AwakeMode
import dev.nucleusframework.energymanager.EnergyManager
import dev.nucleusframework.energymanager.PlatformEnergyManager

internal object LinuxEnergyManager : PlatformEnergyManager {
    private fun callNative(block: () -> Int): EnergyManager.Result {
        if (!NativeLinuxEnergyBridge.isLoaded) {
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
        NativeLinuxEnergyBridge.isLoaded &&
            runCatching { NativeLinuxEnergyBridge.nativeIsSupported() }.getOrDefault(false)

    override fun enableEfficiencyMode() = callNative { NativeLinuxEnergyBridge.nativeEnableEfficiencyMode() }

    override fun disableEfficiencyMode() = callNative { NativeLinuxEnergyBridge.nativeDisableEfficiencyMode() }

    override fun enableLightEfficiencyMode(): EnergyManager.Result =
        callNative { NativeLinuxEnergyBridge.nativeEnableLightEfficiencyMode() }

    override fun disableLightEfficiencyMode(): EnergyManager.Result =
        callNative { NativeLinuxEnergyBridge.nativeDisableLightEfficiencyMode() }

    override fun enableThreadEfficiencyMode(): EnergyManager.Result =
        callNative { NativeLinuxEnergyBridge.nativeEnableThreadEfficiencyMode() }

    override fun disableThreadEfficiencyMode(): EnergyManager.Result =
        callNative { NativeLinuxEnergyBridge.nativeDisableThreadEfficiencyMode() }

    /**
     * [AwakeMode.SYSTEM_ONLY] would need the GNOME/logind inhibitors to be split
     * (INHIBIT_SUSPEND without INHIBIT_IDLE) and the X11 screen-saver backend to be
     * skipped — not implemented yet, so the request is rejected rather than silently
     * keeping the display on as well.
     */
    @Synchronized
    override fun keepAwake(mode: AwakeMode): EnergyManager.Result =
        if (mode == AwakeMode.SYSTEM_ONLY) {
            EnergyManager.Result(false, -1, "AwakeMode.SYSTEM_ONLY is not implemented on Linux yet")
        } else {
            callNative { NativeLinuxEnergyBridge.nativeKeepScreenAwake() }
        }

    @Synchronized
    override fun releaseAwake(): EnergyManager.Result =
        callNative {
            NativeLinuxEnergyBridge.nativeReleaseScreenAwake()
        }

    override fun isAwakeActive(): Boolean =
        NativeLinuxEnergyBridge.isLoaded &&
            runCatching { NativeLinuxEnergyBridge.nativeIsScreenAwakeActive() }.getOrDefault(false)
}
