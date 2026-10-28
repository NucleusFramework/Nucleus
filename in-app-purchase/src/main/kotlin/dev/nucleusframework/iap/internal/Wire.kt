package dev.nucleusframework.iap.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The JSON exchanged with the native backends (Rust `protocol.rs`, Swift `IapStore.swift`).
// Enums travel as strings and unknown values degrade to `Unknown`, so a newer backend never
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
    val hresult: Long? = null,
)

@Serializable
internal data class WireEnvelope(
    val ok: JsonElement? = null,
    val error: WireError? = null,
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
    val groupId: String? = null,
    val introductoryOffer: WireOffer? = null,
    val skuId: String? = null,
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
    val platformId: String? = null,
)

@Serializable
internal data class WirePurchase(
    val productId: String,
    val productType: String = "",
    val transactionId: String? = null,
    val originalTransactionId: String? = null,
    val purchaseTime: Long? = null,
    val expirationTime: Long? = null,
    val revocationTime: Long? = null,
    val quantity: Int = 1,
    val isAutoRenewing: Boolean? = null,
    val state: String = "",
    val jws: String? = null,
    val consumeToken: String? = null,
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
internal data class WirePurchaseOutcome(
    val status: String = "",
    val purchase: WirePurchase? = null,
    val error: WireError? = null,
)

@Serializable
internal data class WireAvailability(
    val state: String = "",
)

@Serializable
internal data class WireConsumeResult(
    val balanceRemaining: Long? = null,
)

@Serializable
internal data class WireEvent(
    val type: String = "",
    val purchase: WirePurchase? = null,
    val purchases: List<WirePurchase>? = null,
)

internal fun jsonArgs(vararg pairs: Pair<String, Any?>): String =
    JsonObject(
        pairs
            .mapNotNull { (key, value) ->
                val element: JsonElement =
                    when (value) {
                        null -> return@mapNotNull null
                        is JsonElement -> value
                        is String -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        is Collection<*> ->
                            kotlinx.serialization.json.JsonArray(
                                value.map { JsonPrimitive(it as String) },
                            )
                        else -> JsonPrimitive(value.toString())
                    }
                key to element
            }.toMap(),
    ).toString()
