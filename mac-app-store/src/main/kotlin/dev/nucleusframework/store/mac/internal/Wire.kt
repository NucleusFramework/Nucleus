package dev.nucleusframework.store.mac.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/** The answer to `dev.forget`: [skipped] names why nothing was attempted. */
@Serializable
internal data class WireForget(
    val removed: Boolean = false,
    val skipped: String? = null,
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

@Serializable
internal data class WirePurchase(
    val productId: String,
    val productType: String = "",
    val transactionId: String,
    val originalTransactionId: String = "",
    val purchaseTime: Long = 0,
    val expirationTime: Long? = null,
    val revocationTime: Long? = null,
    val quantity: Int = 1,
    val environment: String = "",
    val appAccountToken: String? = null,
    val jws: String = "",
)

@Serializable
internal data class WirePurchaseOutcome(
    val status: String = "",
    val purchase: WirePurchase? = null,
)

@Serializable
internal data class WireEntitlement(
    val productId: String,
    val state: String = "",
    val expirationTime: Long? = null,
    val willAutoRenew: Boolean? = null,
    val purchase: WirePurchase? = null,
)

@Serializable
internal data class WireEvent(
    val type: String = "",
    val purchase: WirePurchase? = null,
)

@Serializable
internal data class WireAppTransaction(
    val bundleId: String = "",
    val appId: String? = null,
    val appVersion: String = "",
    val originalAppVersion: String = "",
    val originalPurchaseTime: Long? = null,
    val preorderTime: Long? = null,
    val revocationTime: Long? = null,
    val environment: String = "",
    val jws: String = "",
)

/** The answer to `license`: `status` is valid, revoked, invalid or unavailable. */
@Serializable
internal data class WireLicense(
    val status: String = "",
    val reason: String? = null,
    val app: WireAppTransaction? = null,
)

@Serializable
internal data class WireOpened(
    val opened: Boolean = false,
)

@Serializable
internal data class WireTestingSettings(
    val dialogs: Boolean = true,
    val askToBuy: Boolean = false,
    val billingRetryOnRenewal: Boolean = false,
    val billingGracePeriod: Boolean = false,
    val interruptedPurchases: Boolean = false,
    val timeRate: Int = 0,
    val storefront: String = "",
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
