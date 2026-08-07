package dev.nucleusframework.systeminfo.model

public data class CpuGlobalInfo(
    val globalCpuUsage: Float,
    val physicalCoreCount: Int?,
    val cpus: List<CpuInfo>,
)
