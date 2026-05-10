package dev.nucleusframework.nucleus.systeminfo.model

data class CpuInfo(
    val name: String,
    val vendorId: String,
    val brand: String,
    val frequency: Long,
    val cpuUsage: Float,
)
