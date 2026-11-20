package dev.nucleusframework.store.microsoft.internal

import dev.nucleusframework.store.microsoft.EntitlementState
import dev.nucleusframework.store.microsoft.OfferPaymentMode
import dev.nucleusframework.store.microsoft.PeriodUnit
import dev.nucleusframework.store.microsoft.ProductType
import dev.nucleusframework.store.microsoft.PurchaseState
import dev.nucleusframework.store.microsoft.StoreAvailability
import dev.nucleusframework.store.microsoft.StoreErrorCode
import dev.nucleusframework.store.microsoft.StoreException
import dev.nucleusframework.store.microsoft.StoreUpdateState

// Wire strings to model enums; an unknown value degrades, it never fails.

internal fun WireError.toException(): StoreException =
    StoreException(
        code = storeErrorCode(code),
        message = message.ifBlank { code },
        platformCode = hresult?.toInt(),
    )

internal fun storeErrorCode(code: String): StoreErrorCode =
    when (code) {
        "unsupported" -> StoreErrorCode.Unsupported
        "notPackaged" -> StoreErrorCode.NotPackaged
        "notAssociated" -> StoreErrorCode.NotAssociated
        "noOwnerWindow" -> StoreErrorCode.NoOwnerWindow
        "productNotFound" -> StoreErrorCode.ProductNotFound
        "network" -> StoreErrorCode.Network
        "server" -> StoreErrorCode.Server
        "insufficientQuantity" -> StoreErrorCode.InsufficientQuantity
        "verificationFailed" -> StoreErrorCode.VerificationFailed
        "invalidArgument" -> StoreErrorCode.InvalidArgument
        "updateBlocked" -> StoreErrorCode.UpdateBlocked
        // A cancellation the app did not ask for: the OS aborted the operation.
        "platform", "cancelled" -> StoreErrorCode.Platform
        else -> StoreErrorCode.Internal
    }

internal fun updateState(value: String?): StoreUpdateState? =
    when (value) {
        "pending" -> StoreUpdateState.Pending
        "downloading" -> StoreUpdateState.Downloading
        "installing" -> StoreUpdateState.Installing
        "completed" -> StoreUpdateState.Completed
        "upToDate" -> StoreUpdateState.UpToDate
        "cancelled" -> StoreUpdateState.Cancelled
        // "failed" and unknown states: the operation's own result says what went wrong.
        else -> null
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
        "minute" -> PeriodUnit.Minute
        "hour" -> PeriodUnit.Hour
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

internal fun purchaseState(value: String): PurchaseState =
    when (value) {
        "pending" -> PurchaseState.Pending
        "expired" -> PurchaseState.Expired
        "revoked" -> PurchaseState.Revoked
        else -> PurchaseState.Purchased
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

internal fun availability(value: String): StoreAvailability =
    when (value) {
        "available" -> StoreAvailability.Available
        "paymentsRestricted" -> StoreAvailability.PaymentsRestricted
        "notPackaged" -> StoreAvailability.NotPackaged
        else -> StoreAvailability.Unsupported
    }

/** A decimal the backend normalized (`"1234.56"`); anything else is dropped, not guessed. */
