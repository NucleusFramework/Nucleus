package dev.nucleusframework.systeminfo.model

public data class GpuInfo(
    val name: String,
    val vendorId: Long,
    val deviceId: Long,
    val dedicatedVideoMemory: Long,
    val dedicatedSystemMemory: Long,
    val sharedSystemMemory: Long,
    val driverVersion: String? = null,
    val temperature: Float? = null,
    val gpuUsage: Float? = null,
    val memoryUsed: Long? = null,
    val coreClockMhz: Int? = null,
    val memoryClockMhz: Int? = null,
    val fanSpeedPercent: Float? = null,
    val powerDrawWatts: Float? = null,
)
