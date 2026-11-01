package dev.nucleusframework.store.mac.internal

import dev.nucleusframework.store.mac.IntroductoryOffer
import dev.nucleusframework.store.mac.OfferPaymentMode
import dev.nucleusframework.store.mac.PeriodUnit
import dev.nucleusframework.store.mac.ProductType
import dev.nucleusframework.store.mac.StoreAvailability
import dev.nucleusframework.store.mac.StoreErrorCode
import dev.nucleusframework.store.mac.StoreException
import dev.nucleusframework.store.mac.StoreProduct
import dev.nucleusframework.store.mac.SubscriptionInfo
import dev.nucleusframework.store.mac.SubscriptionPeriod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

// The JSON exchanged with the Swift bridge (`MacStore.swift`).
// Enums travel as strings and unknown values degrade to `Unknown`, so a newer bridge never
// breaks an older Kotlin side and vice versa.

internal val WireJson =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

@Serializable
internal data class WireError(
    val code: String = "internal",
    val message: String = "",
    val platformCode: Int? = null,
)

@Serializable
internal data class WireEnvelope(
    val ok: JsonElement? = null,
    val error: WireError? = null,
)

@Serializable
internal data class WireAvailability(
    val state: String = "",
)

@Serializable
internal data class WirePeriod(
    val value: Int = 0,
    val unit: String = "",
)

@Serializable
internal data class WireOffer(
    val displayPrice: String = "",
    val price: String? = null,
    val period: WirePeriod = WirePeriod(),
    val periodCount: Int = 1,
    val paymentMode: String = "",
)

@Serializable
internal data class WireSubscription(
    val period: WirePeriod = WirePeriod(),
    val groupId: String = "",
    val introductoryOffer: WireOffer? = null,
)

@Serializable
internal data class WireProduct(
    val id: String,
    val title: String = "",
    val description: String = "",
    val type: String = "",
    val displayPrice: String = "",
    val price: String? = null,
    val currencyCode: String? = null,
    val subscription: WireSubscription? = null,
)

internal fun jsonArgs(vararg pairs: Pair<String, Any?>): String =
    JsonObject(
        pairs
            .mapNotNull { (key, value) ->
                val element: JsonElement =
                    when (value) {
                        null -> return@mapNotNull null
                        is String -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        is Collection<*> -> JsonArray(value.map { JsonPrimitive(it as String) })
                        else -> JsonPrimitive(value.toString())
                    }
                key to element
            }.toMap(),
    ).toString()

// -- mapping ------------------------------------------------------------------------------------

internal fun WireError.toException(): StoreException =
    StoreException(
        code = storeErrorCode(code),
        message = message.ifBlank { code },
        platformCode = platformCode,
    )

internal fun storeErrorCode(code: String): StoreErrorCode =
    when (code) {
        "unsupported" -> StoreErrorCode.Unsupported
        "notBundled" -> StoreErrorCode.NotBundled
        "notAvailableInStorefront" -> StoreErrorCode.NotAvailableInStorefront
        "network" -> StoreErrorCode.Network
        "invalidArgument" -> StoreErrorCode.InvalidArgument
        // A cancellation the app did not ask for: StoreKit aborted the operation.
        "platform", "cancelled" -> StoreErrorCode.Platform
        else -> StoreErrorCode.Internal
    }

internal fun availability(value: String): StoreAvailability =
    when (value) {
        "available" -> StoreAvailability.Available
        "paymentsRestricted" -> StoreAvailability.PaymentsRestricted
        "notBundled" -> StoreAvailability.NotBundled
        else -> StoreAvailability.Unsupported
    }

internal fun productType(value: String): ProductType =
    when (value) {
        "consumable" -> ProductType.Consumable
        "nonConsumable" -> ProductType.NonConsumable
        "autoRenewable" -> ProductType.AutoRenewableSubscription
        "nonRenewing" -> ProductType.NonRenewingSubscription
        else -> ProductType.Unknown
    }

internal fun periodUnit(value: String): PeriodUnit =
    when (value) {
        "day" -> PeriodUnit.Day
        "week" -> PeriodUnit.Week
        "month" -> PeriodUnit.Month
        "year" -> PeriodUnit.Year
        else -> PeriodUnit.Unknown
    }

internal fun paymentMode(value: String): OfferPaymentMode =
    when (value) {
        "freeTrial" -> OfferPaymentMode.FreeTrial
        "payAsYouGo" -> OfferPaymentMode.PayAsYouGo
        "payUpFront" -> OfferPaymentMode.PayUpFront
        else -> OfferPaymentMode.Unknown
    }

/** A decimal the bridge wrote with `Decimal.description` (`"4.99"`); anything else is dropped. */
internal fun decimal(value: String?): BigDecimal? = value?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull()

internal fun WirePeriod.toPeriod() = SubscriptionPeriod(value, periodUnit(unit))

internal fun WireProduct.toProduct(): StoreProduct =
    StoreProduct(
        id = id,
        title = title,
        description = description,
        type = productType(type),
        displayPrice = displayPrice,
        price = decimal(price),
        currencyCode = currencyCode?.takeIf { it.isNotBlank() },
        subscription =
            subscription?.let { sub ->
                SubscriptionInfo(
                    period = sub.period.toPeriod(),
                    groupId = sub.groupId,
                    introductoryOffer =
                        sub.introductoryOffer?.let {
                            IntroductoryOffer(
                                displayPrice = it.displayPrice,
                                price = decimal(it.price),
                                period = it.period.toPeriod(),
                                periodCount = it.periodCount,
                                paymentMode = paymentMode(it.paymentMode),
                            )
                        },
                )
            },
    )
