package dev.nucleusframework.nucleus.systeminfo.model

data class ConnectivityInfo(
    val isConnected: Boolean,
    val meteredStatus: MeteredStatus,
)

enum class MeteredStatus {
    NOT_AVAILABLE,
    UNKNOWN,
    UNMETERED,
    METERED,
}
