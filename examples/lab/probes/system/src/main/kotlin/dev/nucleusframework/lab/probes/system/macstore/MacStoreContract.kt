package dev.nucleusframework.lab.probes.system.macstore

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.plusCall
import dev.nucleusframework.lab.core.mvi.plusDelivery
import dev.nucleusframework.store.mac.AppLicense
import dev.nucleusframework.store.mac.AppTransactionInfo
import dev.nucleusframework.store.mac.Purchase
import dev.nucleusframework.store.mac.StoreAvailability
import dev.nucleusframework.store.mac.StoreProduct

@Immutable
data class MacStoreState(
    val supported: Availability = Availability.Unknown,
    /** A StoreKit configuration serves the store (`runStoreKit`). */
    val testing: Boolean = false,
    val availability: StoreAvailability? = null,
    val productIds: String = DEFAULT_PRODUCT_IDS,
    val products: List<StoreProduct> = emptyList(),
    val purchases: List<Purchase> = emptyList(),
    val app: AppTransactionInfo? = null,
    val license: AppLicense? = null,
    /** The product the store-side actions apply to. */
    val actionProductId: String = "premium.monthly",
    /** The StoreKit Testing switches, read from the session (they outlive the Lab). */
    val switches: Map<StoreSwitch, Boolean> = StoreSwitch.entries.associateWith { it.default },
    val calls: List<CallRecord> = emptyList(),
    val updates: List<Delivery> = emptyList(),
) {
    companion object {
        /** The products of the Lab's `NucleusLab.storekit`, one of each kind. */
        const val DEFAULT_PRODUCT_IDS = "pro, coins, premium.monthly"
    }
}

/** StoreKit Testing settings the probe can flip. */
enum class StoreSwitch(
    val label: String,
    val default: Boolean,
) {
    Sheets("Purchase sheets", true),
    AskToBuy("Ask to Buy", false),
    BillingRetry("Renewals fail on billing", false),
    GracePeriod("Grace period on billing failure", false),
}

/** Store-side events of StoreKit Testing, on the product typed in the probe. */
enum class StoreAction(
    val label: String,
) {
    Refund("Refund"),
    ForceRenewal("Renew now"),
    Expire("Expire"),
    BuyOutside("Buy outside the app"),
    ApproveAskToBuy("Approve Ask to Buy"),
    ResolveBillingIssue("Resolve billing issue"),
}

/** The ids typed in [MacStoreState.productIds]: trimmed, blanks and repeats dropped. */
fun parseProductIds(text: String): List<String> =
    text
        .split(',', ' ', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

/** One line for the purchases list. */
fun Purchase.summary(): String =
    buildString {
        append("$productType · #$transactionId · $environment")
        if (quantity > 1) append(" · ×$quantity")
        expirationTime?.let { append(" · until $it") }
        revocationTime?.let { append(" · revoked $it") }
    }

sealed interface MacStoreIntent {
    data object Refresh : MacStoreIntent

    data class SetProductIds(
        val text: String,
    ) : MacStoreIntent

    data object LoadProducts : MacStoreIntent

    data object ListPurchases : MacStoreIntent

    data class Entitlement(
        val productId: String,
    ) : MacStoreIntent

    data class Consume(
        val purchase: Purchase,
    ) : MacStoreIntent

    /** [window] is `TaoWindow.nativeHandle`; `0` lets the module pick the Lab's key window. */
    data class Buy(
        val productId: String,
        val window: Long,
    ) : MacStoreIntent

    data object ReadAppTransaction : MacStoreIntent

    data object CheckLicense : MacStoreIntent

    data object OpenStorePage : MacStoreIntent

    data class Switch(
        val switch: StoreSwitch,
        val enabled: Boolean,
    ) : MacStoreIntent

    data class Act(
        val action: StoreAction,
        val productId: String,
    ) : MacStoreIntent

    data object ClearTransactions : MacStoreIntent

    data class SetActionProduct(
        val productId: String,
    ) : MacStoreIntent
}

sealed interface MacStoreEvent {
    data class Ready(
        val supported: Availability,
        val testing: Boolean,
    ) : MacStoreEvent

    data class AvailabilityRead(
        val availability: StoreAvailability,
    ) : MacStoreEvent

    data class ProductIdsChanged(
        val text: String,
    ) : MacStoreEvent

    data class ProductsLoaded(
        val products: List<StoreProduct>,
    ) : MacStoreEvent

    data class PurchasesListed(
        val purchases: List<Purchase>,
    ) : MacStoreEvent {
        // The timeline prints events: keep the signed transactions out of it.
        override fun toString(): String = "PurchasesListed(${purchases.map { "${it.productId}#${it.transactionId}" }})"
    }

    data class AppTransactionRead(
        val app: AppTransactionInfo,
    ) : MacStoreEvent {
        override fun toString(): String = "AppTransactionRead(${app.bundleId}, ${app.environment})"
    }

    data class LicenseChecked(
        val license: AppLicense,
    ) : MacStoreEvent {
        override fun toString(): String = "LicenseChecked(${license.status}, ${license.reason})"
    }

    data class ActionProductChanged(
        val productId: String,
    ) : MacStoreEvent

    data class Switched(
        val switch: StoreSwitch,
        val enabled: Boolean,
    ) : MacStoreEvent

    data class Called(
        val call: String,
        val outcome: CallOutcome,
        val returned: String? = null,
    ) : MacStoreEvent

    /** A purchase update the App Store sent without being asked. */
    data class Delivered(
        val delivery: Delivery,
    ) : MacStoreEvent
}

object MacStoreReducer : Reducer<MacStoreState, MacStoreEvent> {
    override fun reduce(
        state: MacStoreState,
        event: MacStoreEvent,
    ): MacStoreState =
        when (event) {
            is MacStoreEvent.Ready -> state.copy(supported = event.supported, testing = event.testing)
            is MacStoreEvent.AvailabilityRead -> state.copy(availability = event.availability)
            is MacStoreEvent.ProductIdsChanged -> state.copy(productIds = event.text)
            is MacStoreEvent.ProductsLoaded -> state.copy(products = event.products)
            is MacStoreEvent.PurchasesListed -> state.copy(purchases = event.purchases)
            is MacStoreEvent.AppTransactionRead -> state.copy(app = event.app)
            is MacStoreEvent.LicenseChecked -> state.copy(license = event.license)
            is MacStoreEvent.ActionProductChanged -> state.copy(actionProductId = event.productId)
            is MacStoreEvent.Switched -> state.copy(switches = state.switches + (event.switch to event.enabled))
            is MacStoreEvent.Called ->
                state.copy(calls = state.calls.plusCall(event.call, event.outcome, event.returned))
            is MacStoreEvent.Delivered -> state.copy(updates = state.updates.plusDelivery(event.delivery))
        }
}
