package dev.nucleusframework.nucleus.systeminfo.model

data class BatteryInfo(
    val stateOfCharge: Float,
    val state: BatteryState,
    val isPluggedIn: Boolean,
    val currentCapacity: Int,
    val maxCapacity: Int,
    val designCapacity: Int,
    val cycleCount: Int,
    val voltage: Int,
    val amperage: Int,
    val temperature: Float?,
    val health: Float,
    val timeToFull: Int?,
    val timeToEmpty: Int?,
    val manufacturer: String?,
    val modelName: String?,
    val serialNumber: String?,
)
