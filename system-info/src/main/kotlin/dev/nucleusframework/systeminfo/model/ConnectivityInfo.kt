package dev.nucleusframework.systeminfo.model

public data class ConnectivityInfo(
    val isConnected: Boolean,
    val meteredStatus: MeteredStatus,
)

public enum class MeteredStatus {
    NOT_AVAILABLE,
    UNKNOWN,
    UNMETERED,
    METERED,
}
