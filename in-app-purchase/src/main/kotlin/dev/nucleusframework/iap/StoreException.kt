package dev.nucleusframework.iap

/** Why a store call failed. */
public enum class StoreErrorCode {
    /** No store on this platform, or the native bridge is missing. */
    Unsupported,

    /** Windows without package identity (see [StoreAvailability.NotPackaged]). */
    NotPackaged,

    /**
     * The store has no listing for this app (Windows `0x803F6107`): an unpublished app, or a
     * package whose identity does not match its Partner Center reservation.
     */
    NotAssociated,

    /** Windows: no visible window of this app to parent the purchase UI on. */
    NoOwnerWindow,

    /** The store has no product with that id for this app. */
    ProductNotFound,

    /** The store's service could not be reached. */
    Network,

    /** The store's service answered with an error. */
    Server,

    /** Windows: consuming more units than a Store-managed consumable has left. */
    InsufficientQuantity,

    /** StoreKit could not verify the transaction's signature. */
    VerificationFailed,

    /** The call itself was malformed (an empty product id, a zero quantity, a foreign token). */
    InvalidArgument,

    /** Any other error of the store API; [StoreException.platformCode] carries the detail. */
    Platform,

    /** The Store holds an app update until the device is charging or on Wi-Fi. */
    UpdateBlocked,

    /** A bug in this module or its native bridge. */
    Internal,
}

/**
 * A failed store call.
 *
 * @property platformCode the store API's own error: an `HRESULT` on Windows, an `NSError` code
 *   on macOS; `null` when the failure did not come from the store API
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
            platformCode?.let { append(", 0x").append(Integer.toHexString(it).uppercase()) }
            append("): ").append(message)
        }
}
