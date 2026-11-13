package dev.nucleusframework.store.mac

import java.time.Instant

/**
 * The App Store's signed record that this customer got the app itself (StoreKit
 * `AppTransaction`), as opposed to its in-app products. Use it to honour customers who bought an
 * app that later went free with in-app purchases: compare [originalAppVersion] with the version
 * that changed the business model.
 *
 * @property appId the app's Apple ID in App Store Connect; `null` in StoreKit Testing
 * @property appVersion the `CFBundleVersion` of the running app as the App Store knows it
 * @property originalAppVersion the `CFBundleVersion` the customer first got
 * @property originalPurchaseTime when the customer first got the app
 * @property preorderTime when the customer preordered it, if they did
 * @property revocationTime when Apple refunded or revoked the app (macOS 13+ semantics)
 * @property signedAppTransaction the JWS, to verify on a server with the App Store Server API
 */
public data class AppTransactionInfo(
    val bundleId: String,
    val appId: Long?,
    val appVersion: String,
    val originalAppVersion: String,
    val originalPurchaseTime: Instant?,
    val preorderTime: Instant?,
    val revocationTime: Instant?,
    val environment: StoreEnvironment,
    val signedAppTransaction: String,
)
