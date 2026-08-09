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
     * The inhibitor the composite backend takes depends on [mode]: GNOME gets
     * INHIBIT_SUSPEND alone instead of INHIBIT_SUSPEND | INHIBIT_IDLE, the X11
     * screen-saver backend is skipped entirely (it never keeps the system awake),
     * and org.freedesktop.PowerManagement — which inhibits automatic sleep only —
     * joins the chain for the desktops without org.gnome.SessionManager.
     */
    @Synchronized
    override fun keepAwake(mode: AwakeMode): EnergyManager.Result =
        callNative { NativeLinuxEnergyBridge.nativeKeepAwake(mode.nativeCode) }

    @Synchronized
    override fun releaseAwake(): EnergyManager.Result =
        callNative {
            NativeLinuxEnergyBridge.nativeReleaseAwake()
        }

    override fun isAwakeActive(): Boolean =
        NativeLinuxEnergyBridge.isLoaded &&
            runCatching { NativeLinuxEnergyBridge.nativeIsAwakeActive() }.getOrDefault(false)

    /** Mirrors the AWAKE_* constants in the native bridge. */
    private val AwakeMode.nativeCode: Int
        get() =
            when (this) {
                AwakeMode.SYSTEM_AND_DISPLAY -> 0
                AwakeMode.SYSTEM_ONLY -> 1
            }
}
