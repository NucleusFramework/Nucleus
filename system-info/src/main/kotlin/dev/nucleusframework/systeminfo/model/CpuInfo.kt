package dev.nucleusframework.systeminfo.model

public data class CpuInfo(
    val name: String,
    val vendorId: String,
    val brand: String,
    val frequency: Long,
    val cpuUsage: Float,
)
