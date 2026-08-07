package dev.nucleusframework.systeminfo.model

public data class MemoryInfo(
    val totalMemory: Long,
    val freeMemory: Long,
    val availableMemory: Long,
    val usedMemory: Long,
    val totalSwap: Long,
    val freeSwap: Long,
    val usedSwap: Long,
)
