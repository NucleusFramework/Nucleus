package dev.nucleusframework.store.mac

import dev.nucleusframework.store.mac.internal.jsonArgs

/**
 * How fast subscriptions renew under StoreKit Testing (`SKTestSession.TimeRate`).
 *
 * The `MonthlyRenewal*` rates scale every period to a month taking that long; the
 * `OneRenewalEvery*` rates (macOS 13.3+) renew any subscription at that pace, whatever its period.
 */
@Suppress("MagicNumber") // SKTestTimeRate's raw values.
public enum class RenewalRate(
    internal val raw: Int,
) {
    RealTime(0),
    MonthlyRenewalEveryHour(10),
    MonthlyRenewalEveryThirtyMinutes(11),
    MonthlyRenewalEveryFifteenMinutes(12),
    MonthlyRenewalEveryFiveMinutes(13),
    MonthlyRenewalEveryThirtySeconds(15),
    OneRenewalEveryFifteenMinutes(1000),
    OneRenewalEveryFiveMinutes(1001),
    OneRenewalEveryMinute(1002),
    OneRenewalEveryThirtySeconds(1003),
    OneRenewalEveryTenSeconds(1004),
    OneRenewalEveryTwoSeconds(1005),
}

/**
 * The App Store's side of StoreKit Testing, to exercise an app's purchase code without App Store
 * Connect: refunds, renewals, expiries, Ask to Buy approvals, billing failures, purchases made
 * elsewhere. A declined Ask to Buy is not offered: StoreKit Testing reports it to StoreKit 2 as a
 * completed purchase.
 * Reached through [MacAppStore.testing].
 *
 * Works only while a StoreKit configuration serves the store
 * (`-Dnucleus.macAppStore.storeKitConfiguration`, see [MacAppStore]); otherwise every call fails
 * with [StoreErrorCode.InvalidArgument]. The changes it causes reach the app the way real ones
 * would: through [MacAppStorePurchases.purchaseUpdates], [MacAppStorePurchases.entitlement] and
 * [MacAppStorePurchases.purchase] results.
 *
 * ```kotlin
 * if (MacAppStore.testing.isActive) {
 *     MacAppStore.testing.setBillingRetryOnRenewal(true)
 *     MacAppStore.testing.forceRenewal("premium.monthly")
 *     check(MacAppStore.purchases.entitlement("premium.monthly").state == EntitlementState.InBillingRetry)
 * }
 * ```
 */
@Suppress("TooManyFunctions") // One entry point per StoreKit Testing control.
public class MacAppStoreTesting internal constructor() {
    private val backend
        get() = MacAppStore.backend

    /** `true` when a StoreKit configuration serves the store in this process. */
    public val isActive: Boolean
        get() = backend.isTesting

    /** Forgets every transaction: the customer owns nothing and is eligible for every offer again. */
    public suspend fun clearTransactions() {
        backend.devCommand("dev.clearTransactions", "{}")
    }

    /**
     * Shows or skips the purchase sheets. Skipped, [MacAppStorePurchases.purchase] completes at
     * once, as if the customer confirmed: for automated tests.
     */
    public suspend fun setPurchaseSheetsEnabled(enabled: Boolean): Unit = set("dialogs", enabled)

    /** Purchases wait for a parent's approval: they return [PurchaseResult.Pending]. */
    public suspend fun setAskToBuyEnabled(enabled: Boolean): Unit = set("askToBuy", enabled)

    /** Approves the pending Ask to Buy purchase of [productId]; it arrives through purchaseUpdates. */
    public suspend fun approveAskToBuy(productId: String): Unit = act("approveAskToBuy", productId)

    /** Renewals fail on billing and enter [EntitlementState.InBillingRetry]. */
    public suspend fun setBillingRetryOnRenewal(enabled: Boolean): Unit = set("billingRetryOnRenewal", enabled)

    /** With billing retry on, keeps access during the retry: [EntitlementState.InGracePeriod]. */
    public suspend fun setBillingGracePeriod(enabled: Boolean): Unit = set("billingGracePeriod", enabled)

    /** Ends the billing issue of [productId]'s subscription, as if the customer paid. */
    public suspend fun resolveBillingIssue(productId: String): Unit = act("resolveIssue", productId)

    /** Purchases are interrupted (a payment method to confirm) and need [resolveBillingIssue]. */
    public suspend fun setInterruptedPurchasesEnabled(enabled: Boolean): Unit = set("interruptedPurchases", enabled)

    /** How fast subscriptions renew. */
    public suspend fun setRenewalRate(rate: RenewalRate) {
        backend.devCommand("dev.set", jsonArgs("key" to "timeRate", "int" to rate.raw))
    }

    /** The customer's storefront, as an ISO 3166-1 alpha-3 country code (`USA`, `FRA`). */
    public suspend fun setStorefront(countryCode: String) {
        backend.devCommand("dev.set", jsonArgs("key" to "storefront", "string" to countryCode))
    }

    /** Refunds the latest transaction of [productId]; the app gets [PurchaseUpdate.Revoked]. */
    public suspend fun refund(productId: String): Unit = act("refund", productId)

    /**
     * Ends [productId]'s subscription now. Nothing is sent; [MacAppStorePurchases.entitlement]
     * reports Expired once StoreKit refreshes its status, some 15 seconds later (measured).
     */
    public suspend fun expireSubscription(productId: String): Unit = act("expire", productId)

    /** Renews [productId]'s subscription now; the app gets [PurchaseUpdate.Purchased]. */
    public suspend fun forceRenewal(productId: String): Unit = act("forceRenewal", productId)

    /** Turns the renewal of [productId]'s subscription on or off, as the customer would in Settings. */
    public suspend fun setAutoRenew(
        productId: String,
        enabled: Boolean,
    ): Unit = act(if (enabled) "enableAutoRenew" else "disableAutoRenew", productId)

    /**
     * A purchase of [productId] made outside the app (another device, the App Store app); the app
     * gets [PurchaseUpdate.Purchased].
     */
    public suspend fun buyOutsideApp(productId: String): Unit = act("buy", productId)

    private suspend fun set(
        key: String,
        enabled: Boolean,
    ) {
        backend.devCommand("dev.set", jsonArgs("key" to key, "bool" to enabled))
    }

    private suspend fun act(
        action: String,
        productId: String,
    ) {
        if (productId.isBlank()) throw StoreException(StoreErrorCode.InvalidArgument, "productId must not be blank")
        backend.devCommand("dev.act", jsonArgs("action" to action, "productId" to productId))
    }
}
