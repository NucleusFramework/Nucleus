package dev.nucleusframework.nucleus.systeminfo.model

data class ProcessInfo(
    val pid: Long,
    val name: String,
    val exe: String?,
    val memory: Long,
    val virtualMemory: Long,
    val cpuUsage: Float,
    val status: String,
    val startTime: Long,
    val runTime: Long,
    val parentPid: Long?,
    val cmd: List<String>,
    val cwd: String?,
    val root: String?,
)
