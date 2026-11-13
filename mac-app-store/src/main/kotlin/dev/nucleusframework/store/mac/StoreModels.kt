package dev.nucleusframework.store.mac

import java.math.BigDecimal

/** What kind of product a [StoreProduct] is. */
public enum class ProductType {
    /** Bought, used up, and bought again. */
    Consumable,

    /** Bought once and owned for good. */
    NonConsumable,

    /** A subscription that renews until cancelled. */
    AutoRenewableSubscription,

    /** A time-limited pass that does not renew. */
    NonRenewingSubscription,

    /** StoreKit reported a kind this version does not know. */
    Unknown,
}

/** The unit of a [SubscriptionPeriod]. */
public enum class PeriodUnit {
    Day,
    Week,
    Month,
    Year,
    Unknown,
}

/** A billing or offer period: [value] × [unit]. */
public data class SubscriptionPeriod(
    val value: Int,
    val unit: PeriodUnit,
) {
    /** ISO-8601 duration (`P1M`, `P7D`, `P1W`), or `null` for an [PeriodUnit.Unknown] unit. */
    public fun toIso8601(): String? =
        when (unit) {
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
 * The introductory offer of a subscription, reported only while the current customer can still
 * redeem it (StoreKit `isEligibleForIntroOffer`): a customer who already used the group's trial
 * sees none.
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
 * @property groupId the App Store Connect subscription group
 * @property introductoryOffer the offer this customer is eligible for, if any
 */
public data class SubscriptionInfo(
    val period: SubscriptionPeriod,
    val groupId: String,
    val introductoryOffer: IntroductoryOffer?,
)

/**
 * A product as the App Store lists it for the current customer, in their storefront's currency.
 *
 * @property id the App Store Connect product id
 * @property displayPrice the price formatted for the customer's storefront; for a subscription
 *   the recurring price, not a trial's "Free"
 * @property price the same amount as a number
 * @property currencyCode ISO-4217 code of [price]
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
)

/** Whether in-app purchases can work in this process. */
public enum class StoreAvailability {
    Available,

    /** `AppStore.canMakePayments` is false: parental controls or a device management profile. */
    PaymentsRestricted,

    /**
     * The process is not an app bundle, so StoreKit cannot tell which app it is: a bare `java`
     * launch, `./gradlew run`. Package the app (or use the development `.app` with a StoreKit
     * configuration) to reach the store.
     */
    NotBundled,

    /** Not macOS, or the native bridge failed to load. */
    Unsupported,
}
