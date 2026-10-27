package dev.nucleusframework.systeminfo.model

public data class ConnectivityInfo(
    val isConnected: Boolean,
    val meteredStatus: MeteredStatus,
    val level: ConnectivityLevel = ConnectivityLevel.UNKNOWN,
)

public enum class MeteredStatus {
    NOT_AVAILABLE,
    UNKNOWN,
    UNMETERED,
    METERED,
}

/** How far the active connection reaches, as reported by the OS. */
public enum class ConnectivityLevel {
    /** The platform does not report a connectivity level. */
    UNKNOWN,

    /** No network connection at all. */
    NONE,

    /** Connected to a network without a default route (local link only). */
    LOCAL,

    /** A default route exists but the internet is not reachable. */
    LIMITED,

    /** The internet is only reachable after signing in to a captive portal. */
    CAPTIVE_PORTAL,

    /** Full internet access. */
    FULL,
}
