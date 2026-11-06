package dev.nucleusframework.store.microsoft

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** What kind of product a [StoreProduct] or [Purchase] is. */
public enum class ProductType {
    /** Bought, used up with [MicrosoftStorePurchases.consume], and bought again. */
    Consumable,

    /** Bought once and owned for good (a Microsoft Store "durable"). */
    NonConsumable,

    /** A subscription that renews until cancelled. */
    AutoRenewableSubscription,

    /** A time-limited pass that does not renew (StoreKit only). */
    NonRenewingSubscription,

    /** The store reported a kind this version does not know. */
    Unknown,
}

/** The unit of a [SubscriptionPeriod]. Minutes and hours only exist on the Microsoft Store. */
public enum class PeriodUnit {
    Minute,
    Hour,
    Day,
    Week,
    Month,
    Year,
    Unknown,
}

/** A billing or trial period: [value] × [unit]. */
public data class SubscriptionPeriod(
    val value: Int,
    val unit: PeriodUnit,
) {
    /** ISO-8601 duration (`P1M`, `P7D`, `PT15M`), or `null` for an [PeriodUnit.Unknown] unit. */
    public fun toIso8601(): String? =
        when (unit) {
            PeriodUnit.Minute -> "PT${value}M"
            PeriodUnit.Hour -> "PT${value}H"
            PeriodUnit.Day -> "P${value}D"
            PeriodUnit.Week -> "P${value}W"
            PeriodUnit.Month -> "P${value}M"
            PeriodUnit.Year -> "P${value}Y"
            PeriodUnit.Unknown -> null
        }
}

/** How an [IntroductoryOffer] is paid. */
public enum class OfferPaymentMode {
    FreeTrial,

    /** A reduced price for each of [IntroductoryOffer.periodCount] periods. */
    PayAsYouGo,

    /** A reduced price paid once for the whole offer. */
    PayUpFront,
    Unknown,
}

/**
 * An introductory offer the current customer is eligible for. Stores only report offers the
 * customer can still redeem, so a customer who already used a trial sees none.
 */
public data class IntroductoryOffer(
    val displayPrice: String,
    val price: BigDecimal?,
    val period: SubscriptionPeriod,
    val periodCount: Int,
    val paymentMode: OfferPaymentMode,
)

/**
 * Subscription details of a [StoreProduct].
 *
 * @property period the billing period after any introductory offer
 * @property groupId StoreKit subscription group; `null` on the Microsoft Store
 * @property introductoryOffer the offer this customer is eligible for, if any
 */
public data class SubscriptionInfo(
    val period: SubscriptionPeriod,
    val groupId: String?,
    val introductoryOffer: IntroductoryOffer?,
)

/**
 * A product as the store lists it for the current customer, in their currency.
 *
 * @property id the developer-defined product id: the App Store Connect product id, or the
 *   Partner Center add-on's *product id* (`InAppOfferToken`) — never Microsoft's `StoreId`
 * @property displayPrice the price formatted for the customer's locale; for a subscription the
 *   recurring price, not a trial's "Free"
 * @property price the same amount as a number, when the store's string could be read as one
 * @property currencyCode ISO-4217 code, when the store reports it (StoreKit: macOS 13+)
 * @property platformId the store's own identifier (Microsoft `StoreId`); `null` on StoreKit
 */
public data class StoreProduct(
    val id: String,
    val title: String,
    val description: String,
    val type: ProductType,
    val displayPrice: String,
    val price: BigDecimal?,
    val currencyCode: String?,
    val subscription: SubscriptionInfo?,
    val platformId: String?,
)

/** Where a [Purchase] stands. */
public enum class PurchaseState {
    /** Paid and in effect. */
    Purchased,

    /** Waiting on something outside the app (Ask to Buy, a payment method check). */
    Pending,

    /** A subscription or pass that ran out. */
    Expired,

    /** Refunded or revoked by the store. */
    Revoked,
}

/**
 * A purchase the store knows about.
 *
 * What each store can say differs, and nothing is made up to fill the gaps: the Microsoft
 * Store has no transaction ids, purchase dates or signed receipts for add-ons, so those are
 * `null` there.
 *
 * @property transactionId StoreKit `Transaction.id`
 * @property originalTransactionId StoreKit `Transaction.originalID` (stable across renewals)
 * @property isAutoRenewing whether the subscription will renew; `null` when unknown
 * @property signedTransaction StoreKit's JWS (`VerificationResult.jwsRepresentation`), to
 *   verify on your server with the App Store Server API
 * @property consumeToken what [MicrosoftStorePurchases.consume] needs to fulfil a consumable; opaque
 */
public data class Purchase(
    val productId: String,
    val productType: ProductType,
    val transactionId: String?,
    val originalTransactionId: String?,
    val purchaseTime: Instant?,
    val expirationTime: Instant?,
    val revocationTime: Instant?,
    val quantity: Int,
    val isAutoRenewing: Boolean?,
    val state: PurchaseState,
    val signedTransaction: String?,
    val consumeToken: String?,
)

/** The outcome of [MicrosoftStorePurchases.purchase]. Store failures are [Failed], never thrown. */
public sealed interface PurchaseResult {
    /**
     * The customer owns the product.
     *
     * @property alreadyOwned the store refused a second purchase because the customer already
     *   owns it (Microsoft Store `AlreadyPurchased`)
     */
    public data class Success(
        val purchase: Purchase,
        val alreadyOwned: Boolean = false,
    ) : PurchaseResult

    /** Waiting for approval; the result arrives later through [MicrosoftStorePurchases.purchaseUpdates]. */
    public data object Pending : PurchaseResult

    /** The customer closed the purchase sheet. */
    public data object Cancelled : PurchaseResult

    /** The store could not complete the purchase. */
    public data class Failed(
        val error: StoreException,
    ) : PurchaseResult
}

/** How far an [EntitlementStatus] entitles the customer. */
public enum class EntitlementState {
    Active,

    /** Renewal failed but the store keeps access open while it retries (StoreKit). */
    InGracePeriod,

    /** Renewal failed and access has lapsed while the store retries billing (StoreKit). */
    InBillingRetry,
    Expired,
    Revoked,
    NotOwned,
}

/**
 * Whether the customer may use [productId] right now. Consumables are never entitlements: they
 * are [EntitlementState.NotOwned] once consumed, and listed by [MicrosoftStorePurchases.list] until.
 *
 * @property willAutoRenew the subscription's renewal intent, when the store reports it
 * @property purchase the transaction behind an active entitlement
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

/** A purchase change the app did not initiate through [MicrosoftStorePurchases.purchase]. */
public sealed interface PurchaseUpdate {
    /** The product the update is about. */
    public val purchase: Purchase

    /**
     * A new or renewed purchase: a subscription renewal, an approved Ask to Buy, a purchase
     * made on another device or from the store's own UI, an unfinished consumable at launch.
     */
    public data class Purchased(
        override val purchase: Purchase,
    ) : PurchaseUpdate

    /** Refunded, revoked or lapsed: stop granting the product. */
    public data class Revoked(
        override val purchase: Purchase,
    ) : PurchaseUpdate
}

/**
 * The license of the app itself, as opposed to its add-ons: whether the customer bought it, is
 * trying it, or neither.
 *
 * The Store does not stop a trial from running once it ends: [isActive] turns `false` and the
 * app decides what to lock. Read it at launch and after [MicrosoftStorePurchases.appLicenseUpdates].
 *
 * An app the Store did not license — sideloaded, a development build, a package without a
 * listing — reads as active and not a trial, with no [skuStoreId]. Gate on [isFullLicense],
 * which accounts for it, rather than on `isActive && !isTrial`.
 *
 * @property isActive the customer may use the app: a full license, or a trial not yet over
 * @property isTrial the license is a trial (which may be over: check [isActive])
 * @property expirationTime when the license ends — the end of the trial for a trial; `null`
 *   for a license that never ends
 * @property trialTimeRemaining what is left of the trial, counted to [expirationTime] for a
 *   time-limited trial; `null` outside a trial and for an unlimited one
 * @property isTrialOwnedByThisUser the usage-limited trial belongs to the signed-in customer;
 *   always `false` for a time-limited trial (the Store tracks only usage-limited ones per user)
 * @property trialUniqueId identifies the customer's usage-limited trial; `null` for a
 *   time-limited trial, which the Store already ties to the customer's account
 * @property skuStoreId the Store id of the licensed SKU (`9NBLGGH4R315/000N`); `null` when the
 *   Store did not license this install
 */
public data class AppLicense(
    val isActive: Boolean,
    val isTrial: Boolean,
    val expirationTime: Instant?,
    val trialTimeRemaining: Duration?,
    val isTrialOwnedByThisUser: Boolean,
    val trialUniqueId: String?,
    val skuStoreId: String?,
) {
    /**
     * `true` when the customer bought the app: an active Store license that is not a trial.
     * `false` for an install the Store did not license (no [skuStoreId]).
     */
    val isFullLicense: Boolean
        get() = isActive && !isTrial && skuStoreId != null
}

/** Whether in-app purchases can work in this process. */
public enum class StoreAvailability {
    Available,

    /** StoreKit `AppStore.canMakePayments` is false (parental controls, MDM). */
    PaymentsRestricted,

    /**
     * Windows without package identity: the Microsoft Store only serves MSIX/AppX installs.
     * An NSIS/MSI/portable build or a `./gradlew run` lands here.
     */
    NotPackaged,

    /** No store on this platform (Linux), or the native bridge failed to load. */
    Unsupported,
}
