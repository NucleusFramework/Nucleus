package dev.nucleusframework.store.mac

/** Why a store call failed. */
public enum class StoreErrorCode {
    /** Not macOS, or the native bridge is missing. */
    Unsupported,

    /** The process is not an app bundle (see [StoreAvailability.NotBundled]). */
    NotBundled,

    /** The product is not sold in the customer's storefront. */
    NotAvailableInStorefront,

    /** The App Store could not be reached. */
    Network,

    /** The call itself was malformed, or a StoreKit configuration file could not be read. */
    InvalidArgument,

    /** Any other StoreKit error; [StoreException.platformCode] carries the detail. */
    Platform,

    /** A bug in this module or its native bridge. */
    Internal,
}

/**
 * A failed store call.
 *
 * @property platformCode the `NSError` code behind the failure; `null` when it did not come
 *   from StoreKit
 */
public class StoreException(
    public val code: StoreErrorCode,
    message: String,
    public val platformCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    override fun toString(): String =
        buildString {
            append("StoreException(").append(code)
            platformCode?.let { append(", ").append(it) }
            append("): ").append(message)
        }
}
