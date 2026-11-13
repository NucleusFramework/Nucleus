package dev.nucleusframework.store.mac.internal

import dev.nucleusframework.store.mac.AppLicense
import dev.nucleusframework.store.mac.AppTransactionInfo
import dev.nucleusframework.store.mac.EntitlementStatus
import dev.nucleusframework.store.mac.IntroductoryOffer
import dev.nucleusframework.store.mac.Purchase
import dev.nucleusframework.store.mac.StoreProduct
import dev.nucleusframework.store.mac.SubscriptionInfo
import dev.nucleusframework.store.mac.SubscriptionPeriod
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

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

private fun instant(millis: Long?): Instant? = millis?.let(Instant::ofEpochMilli)

internal fun WirePurchase.toPurchase(): Purchase =
    Purchase(
        productId = productId,
        productType = productType(productType),
        transactionId = transactionId,
        originalTransactionId = originalTransactionId.ifBlank { transactionId },
        purchaseTime = Instant.ofEpochMilli(purchaseTime),
        expirationTime = instant(expirationTime),
        revocationTime = instant(revocationTime),
        quantity = quantity,
        environment = environment(environment),
        appAccountToken = appAccountToken?.let { runCatching { UUID.fromString(it) }.getOrNull() },
        signedTransaction = jws,
    )

internal fun WireEntitlement.toStatus(): EntitlementStatus =
    EntitlementStatus(
        productId = productId,
        state = entitlementState(state),
        expirationTime = instant(expirationTime),
        willAutoRenew = willAutoRenew,
        purchase = purchase?.toPurchase(),
    )

internal fun WireAppTransaction.toInfo(): AppTransactionInfo =
    AppTransactionInfo(
        bundleId = bundleId,
        appId = appId?.toLongOrNull(),
        appVersion = appVersion,
        originalAppVersion = originalAppVersion,
        originalPurchaseTime = instant(originalPurchaseTime),
        preorderTime = instant(preorderTime),
        revocationTime = instant(revocationTime),
        environment = environment(environment),
        signedAppTransaction = jws,
    )

internal fun WireLicense.toLicense(): AppLicense =
    AppLicense(
        status = licenseStatus(status),
        reason = reason,
        appTransaction = app?.toInfo(),
    )
