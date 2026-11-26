package dev.nucleusframework.store.mac

/** How [MacAppStore.appLicense] judged the app's proof of purchase. */
public enum class AppLicenseStatus {
    /** Signed by the App Store for this app, on this Mac, and not refunded. */
    Valid,

    /** Apple refunded or revoked the app. */
    Revoked,

    /**
     * A proof exists but fails a check: its signature, made for another app, or made for another
     * Mac (an app bundle copied with its data). Treat the copy as not bought.
     */
    Invalid,

    /**
     * No proof can be checked now: the first launch offline, no App Store account, before macOS
     * 13, outside an app bundle. Not evidence of a copy; the app chooses how long to trust a
     * previous [Valid].
     */
    Unavailable,
}

/**
 * Whether this copy of the app was got from the Mac App Store by this customer. The Mac App Store
 * neither encrypts apps nor issues a license, so a copied bundle runs; checking this is how an
 * app sold on it tells the two apart.
 *
 * @property status the outcome of the checks
 * @property reason why the status is not [AppLicenseStatus.Valid], for logs
 * @property appTransaction the proof itself, when StoreKit returned one (even an invalid one)
 */
public data class AppLicense(
    val status: AppLicenseStatus,
    val reason: String?,
    val appTransaction: AppTransactionInfo?,
) {
    /** Whether the app may run as bought. */
    val isValid: Boolean get() = status == AppLicenseStatus.Valid
}
