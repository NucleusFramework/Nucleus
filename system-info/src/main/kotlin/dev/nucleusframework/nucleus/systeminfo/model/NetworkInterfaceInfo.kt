package dev.nucleusframework.nucleus.systeminfo.model

data class NetworkInterfaceInfo(
    val name: String,
    val receivedBytes: Long,
    val transmittedBytes: Long,
    val receivedPackets: Long,
    val transmittedPackets: Long,
    val errorsOnReceived: Long,
    val errorsOnTransmitted: Long,
    val macAddress: String,
    val mtu: Long,
)
