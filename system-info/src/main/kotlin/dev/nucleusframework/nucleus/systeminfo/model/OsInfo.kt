package dev.nucleusframework.nucleus.systeminfo.model

data class OsInfo(
    val name: String?,
    val kernelVersion: String?,
    val osVersion: String?,
    val longOsVersion: String?,
    val distributionId: String?,
    val hostName: String?,
    val cpuArch: String?,
    val uptime: Long,
    val bootTime: Long,
)
