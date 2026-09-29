package dev.nucleusframework.location

/** How precise a fix the app asks for. */
public enum class LocationAccuracy {
    /** The best the device can do: GNSS where there is one. Costs the most power. */
    Precise,

    /** City-level (roughly a kilometer). Often answered from Wi-Fi or the network alone. */
    Approximate,
}

/** When the app needs the location. */
public enum class LocationAccess {
    /** While the app is in use. */
    Foreground,

    /**
     * Also while it is in the background. Only mobile platforms tell the two apart: desktop apps
     * keep their access whether they are focused or not.
     */
    Background,
}

/** What the user (or the system policy) currently allows. */
public enum class LocationAuthorization {
    /**
     * Not asked yet — or not knowable without asking: Windows and Linux only report a grant once
     * [LocationProvider.requestAuthorization] or a location request obtained one in this process.
     */
    NotDetermined,

    /** The user refused, or turned location off for this app. */
    Denied,

    /** A policy (parental controls, MDM, missing hardware or service) forbids it. */
    Restricted,

    /** Allowed while the app is in use. */
    Foreground,

    /** Allowed in the background too. */
    Background,
    ;

    /** Whether locations may be requested now. */
    public val isGranted: Boolean
        get() = this == Foreground || this == Background
}

/** Why a location request failed; carried by [LocationException]. */
public enum class LocationError {
    /** The user or the system refused access. */
    AuthorizationDenied,

    /** No fix right now (no signal, service starting up); retrying later may succeed. */
    TemporarilyUnavailable,

    /** Location is off, or there is no location service on this machine. */
    PermanentlyUnavailable,

    /** The network-based provider could not be reached. */
    Network,

    /** No fix arrived within the request's timeout. */
    Timeout,

    /** Anything the platform did not explain. */
    Unknown,
}

/** A failed location request. */
public class LocationException(
    /** The category of the failure. */
    public val error: LocationError,
    message: String? = null,
) : Exception(message ?: error.name)
