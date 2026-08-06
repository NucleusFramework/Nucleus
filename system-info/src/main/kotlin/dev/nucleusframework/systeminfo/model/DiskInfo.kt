package dev.nucleusframework.systeminfo.model

public data class DiskInfo(
    val name: String,
    val fileSystem: String,
    val mountPoint: String,
    val totalSpace: Long,
    val availableSpace: Long,
    val kind: String,
    val isRemovable: Boolean,
    val isReadOnly: Boolean,
)
