package dev.nucleusframework.store.mac.internal

import dev.nucleusframework.store.mac.EntitlementState
import dev.nucleusframework.store.mac.OfferPaymentMode
import dev.nucleusframework.store.mac.PeriodUnit
import dev.nucleusframework.store.mac.ProductType
import dev.nucleusframework.store.mac.StoreAvailability
import dev.nucleusframework.store.mac.StoreEnvironment
import dev.nucleusframework.store.mac.StoreErrorCode
import dev.nucleusframework.store.mac.StoreException

// Wire strings to model enums; an unknown value degrades, it never fails.

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
        "productNotFound" -> StoreErrorCode.ProductNotFound
        "purchaseNotAllowed" -> StoreErrorCode.PurchaseNotAllowed
        "verificationFailed" -> StoreErrorCode.VerificationFailed
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

internal fun environment(value: String): StoreEnvironment =
    when (value) {
        "production" -> StoreEnvironment.Production
        "sandbox" -> StoreEnvironment.Sandbox
        "xcode" -> StoreEnvironment.Xcode
        else -> StoreEnvironment.Unknown
    }

internal fun entitlementState(value: String): EntitlementState =
    when (value) {
        "active" -> EntitlementState.Active
        "inGracePeriod" -> EntitlementState.InGracePeriod
        "inBillingRetry" -> EntitlementState.InBillingRetry
        "expired" -> EntitlementState.Expired
        "revoked" -> EntitlementState.Revoked
        else -> EntitlementState.NotOwned
    }
