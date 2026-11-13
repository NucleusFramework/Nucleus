package dev.nucleusframework.store.mac

import java.time.Instant
import java.util.UUID

/** Which App Store signed a [Purchase]. */
public enum class StoreEnvironment {
    /** The real App Store: the customer paid. */
    Production,

    /** Apple's sandbox: a development or TestFlight build with a sandbox account. */
    Sandbox,

    /** StoreKit Testing, from a `.storekit` configuration (the module's development mode). */
    Xcode,
    Unknown,
}

/**
 * A StoreKit transaction the customer owns or owned.
 *
 * @property transactionId StoreKit `Transaction.id`
 * @property originalTransactionId StoreKit `Transaction.originalID`, stable across renewals
 * @property expirationTime when a subscription period ends; `null` for other products
 * @property revocationTime when Apple refunded or revoked it
 * @property quantity units bought (consumables)
 * @property appAccountToken the token given in [PurchaseOptions.appAccountToken]
 * @property signedTransaction the JWS (`VerificationResult.jwsRepresentation`), to verify on a
 *   server with the App Store Server API
 */
public data class Purchase(
    val productId: String,
    val productType: ProductType,
    val transactionId: String,
    val originalTransactionId: String,
    val purchaseTime: Instant,
    val expirationTime: Instant?,
    val revocationTime: Instant?,
    val quantity: Int,
    val environment: StoreEnvironment,
    val appAccountToken: UUID?,
    val signedTransaction: String,
)

/** The outcome of [MacAppStorePurchases.purchase]. Store failures are [Failed], never thrown. */
public sealed interface PurchaseResult {
    /** The customer owns the product; StoreKit verified the transaction. */
    public data class Success(
        val purchase: Purchase,
    ) : PurchaseResult

    /** Waiting for approval (Ask to Buy, Strong Customer Authentication); it completes later. */
    public data object Pending : PurchaseResult

    /** The customer closed the purchase sheet. */
    public data object Cancelled : PurchaseResult

    /** StoreKit could not complete the purchase. */
    public data class Failed(
        val error: StoreException,
    ) : PurchaseResult
}

/** A purchase change the app did not initiate through [MacAppStorePurchases.purchase]. */
public sealed interface PurchaseUpdate {
    /** The transaction the update is about. */
    public val purchase: Purchase

    /**
     * A new or renewed purchase: a subscription renewal, an approved Ask to Buy, a purchase made
     * on another device or in the App Store app, a consumable not yet consumed at launch, a
     * purchase completed after its `purchase()` call was cancelled.
     */
    public data class Purchased(
        override val purchase: Purchase,
    ) : PurchaseUpdate

    /** Refunded or revoked by Apple: stop granting the product. */
    public data class Revoked(
        override val purchase: Purchase,
    ) : PurchaseUpdate
}

/** How far an [EntitlementStatus] entitles the customer. */
public enum class EntitlementState {
    Active,

    /** A renewal failed but Apple keeps access open while it retries billing. */
    InGracePeriod,

    /** A renewal failed and access has lapsed while Apple retries billing. */
    InBillingRetry,
    Expired,

    /** Refunded or revoked by Apple. */
    Revoked,
    NotOwned,
}

/**
 * Whether the customer may use [productId] right now. Consumables are never entitlements: they
 * are [EntitlementState.NotOwned], and listed by [MacAppStorePurchases.list] until consumed.
 *
 * @property willAutoRenew the subscription's renewal intent; `null` outside subscriptions
 * @property purchase the transaction the state comes from
 */
public data class EntitlementStatus(
    val productId: String,
    val state: EntitlementState,
    val expirationTime: Instant?,
    val willAutoRenew: Boolean?,
    val purchase: Purchase?,
) {
    /** `true` while the customer should get the product: [EntitlementState.Active] or in grace. */
    val isActive: Boolean
        get() = state == EntitlementState.Active || state == EntitlementState.InGracePeriod
}

/**
 * Options of [MacAppStorePurchases.purchase].
 *
 * @property window the window the purchase sheet attaches to: `TaoWindow.nativeHandle` (an
 *   `NSView *`) or an `NSWindow *`. Unset: the app's key window, else its main or first visible
 *   window
 * @property quantity units to buy, for a consumable
 * @property appAccountToken links the transaction to an account of your own; it comes back in
 *   [Purchase.appAccountToken] and in App Store Server notifications
 */
public class PurchaseOptions internal constructor() {
    public var window: Long? = null
    public var quantity: Int = 1
    public var appAccountToken: UUID? = null
}
