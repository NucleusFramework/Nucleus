package dev.nucleusframework.iap.internal

import dev.nucleusframework.iap.AppLicense
import dev.nucleusframework.iap.EntitlementStatus
import dev.nucleusframework.iap.IntroductoryOffer
import dev.nucleusframework.iap.Purchase
import dev.nucleusframework.iap.StoreProduct
import dev.nucleusframework.iap.SubscriptionInfo
import dev.nucleusframework.iap.SubscriptionPeriod
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

internal fun decimal(value: String?): BigDecimal? = value?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull()

internal fun instant(millis: Long?): Instant? = millis?.let(Instant::ofEpochMilli)

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
        platformId = platformId,
    )

internal fun WirePurchase.toPurchase(): Purchase =
    Purchase(
        productId = productId,
        productType = productType(productType),
        transactionId = transactionId,
        originalTransactionId = originalTransactionId,
        purchaseTime = instant(purchaseTime),
        expirationTime = instant(expirationTime),
        revocationTime = instant(revocationTime),
        quantity = quantity,
        isAutoRenewing = isAutoRenewing,
        state = purchaseState(state),
        signedTransaction = jws,
        consumeToken = consumeToken,
    )

internal fun WireEntitlement.toStatus(): EntitlementStatus =
    EntitlementStatus(
        productId = productId,
        state = entitlementState(state),
        expirationTime = instant(expirationTime),
        willAutoRenew = willAutoRenew,
        purchase = purchase?.toPurchase(),
    )

internal fun WireAppLicense.toAppLicense(): AppLicense =
    AppLicense(
        isActive = isActive,
        isTrial = isTrial,
        expirationTime = instant(expirationTime),
        trialTimeRemaining = trialTimeRemaining?.let(Duration::ofMillis),
        isTrialOwnedByThisUser = isTrialOwnedByThisUser,
        trialUniqueId = trialUniqueId?.takeIf { it.isNotBlank() },
        skuStoreId = skuStoreId?.takeIf { it.isNotBlank() },
    )
