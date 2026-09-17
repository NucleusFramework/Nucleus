package dev.nucleusframework.energymanager

internal interface PlatformEnergyManager {
    fun isAvailable(): Boolean

    fun enableEfficiencyMode(): EnergyManager.Result

    fun disableEfficiencyMode(): EnergyManager.Result

    fun enableLightEfficiencyMode(): EnergyManager.Result =
        EnergyManager.Result(false, -1, "Light efficiency mode not supported on this platform")

    fun disableLightEfficiencyMode(): EnergyManager.Result =
        EnergyManager.Result(false, -1, "Light efficiency mode not supported on this platform")

    fun enableThreadEfficiencyMode(): EnergyManager.Result

    fun disableThreadEfficiencyMode(): EnergyManager.Result

    fun keepAwake(mode: AwakeMode): EnergyManager.Result

    fun releaseAwake(): EnergyManager.Result

    fun isAwakeActive(): Boolean
}
