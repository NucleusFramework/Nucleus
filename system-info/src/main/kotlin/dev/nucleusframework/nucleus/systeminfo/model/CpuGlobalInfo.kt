package dev.nucleusframework.nucleus.systeminfo.model

data class CpuGlobalInfo(
    val globalCpuUsage: Float,
    val physicalCoreCount: Int?,
    val cpus: List<CpuInfo>,
)
