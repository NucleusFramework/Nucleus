package dev.nucleusframework.nucleus.servicemanagement

/**
 * Maps `SMAppServiceStatus` from Apple's ServiceManagement framework.
 *
 * Describes the current registration state of an app service.
 */
@Suppress("MagicNumber")
public enum class AppServiceStatus(
    public val rawValue: Int,
) {
    /** The service has not been registered, or was unregistered. */
    NOT_REGISTERED(0),

    /** The service is registered and eligible to run. */
    ENABLED(1),

    /** The service is registered but requires user approval in System Settings. */
    REQUIRES_APPROVAL(2),

    /** The framework could not locate the service (plist missing or identifier wrong). */
    NOT_FOUND(3),
    ;

    public companion object {
        public fun fromRawValue(value: Int): AppServiceStatus =
            entries.firstOrNull { it.rawValue == value } ?: NOT_FOUND
    }
}
