package dev.nucleusframework.lab.probes.system.purchases

import androidx.compose.runtime.Immutable
import dev.nucleusframework.iap.AppLicense
import dev.nucleusframework.iap.Purchase
import dev.nucleusframework.iap.StoreAvailability
import dev.nucleusframework.iap.StoreProduct
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.plusCall
import dev.nucleusframework.lab.core.mvi.plusDelivery

@Immutable
data class PurchasesState(
    val supported: Availability = Availability.Unknown,
    val availability: StoreAvailability? = null,
    val license: AppLicense? = null,
    val productIds: String = DEFAULT_PRODUCT_IDS,
    val products: List<StoreProduct> = emptyList(),
    val purchases: List<Purchase> = emptyList(),
    val calls: List<CallRecord> = emptyList(),
    val updates: List<Delivery> = emptyList(),
) {
    companion object {
        /** The add-ons reserved in Partner Center for the Lab, one of each kind. */
        const val DEFAULT_PRODUCT_IDS = "pro, coins, credits, sub_monthly"
    }
}

/** The ids typed in [PurchasesState.productIds]: trimmed, blanks and repeats dropped. */
fun parseProductIds(text: String): List<String> =
    text
        .split(',', ' ', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

/** One line for the timeline and the update log. */
fun AppLicense.summary(): String =
    when {
        isFullLicense -> "full license"
        isTrial && isActive -> "trial, ${trialTimeRemaining?.let { "${it.toHours()} h left" } ?: "unlimited"}"
        isTrial -> "trial over"
        skuStoreId == null -> "not licensed by the Store (active=$isActive)"
        else -> "inactive"
    }

sealed interface PurchasesIntent {
    data object Refresh : PurchasesIntent

    data class SetProductIds(
        val text: String,
    ) : PurchasesIntent

    data object LoadProducts : PurchasesIntent

    data object ListPurchases : PurchasesIntent

    data object Restore : PurchasesIntent

    data class Entitlement(
        val productId: String,
    ) : PurchasesIntent

    data class Consume(
        val purchase: Purchase,
    ) : PurchasesIntent

    /** [hwnd] parents the Store's dialog; `0` lets the module pick the Lab's foreground window. */
    data class BuyApp(
        val hwnd: Long,
    ) : PurchasesIntent

    data class Buy(
        val productId: String,
        val hwnd: Long,
    ) : PurchasesIntent
}

sealed interface PurchasesEvent {
    data class Ready(
        val supported: Availability,
    ) : PurchasesEvent

    data class AvailabilityRead(
        val availability: StoreAvailability,
    ) : PurchasesEvent

    data class LicenseRead(
        val license: AppLicense,
    ) : PurchasesEvent

    data class ProductIdsChanged(
        val text: String,
    ) : PurchasesEvent

    data class ProductsLoaded(
        val products: List<StoreProduct>,
    ) : PurchasesEvent

    data class PurchasesListed(
        val purchases: List<Purchase>,
    ) : PurchasesEvent

    data class Called(
        val call: String,
        val outcome: CallOutcome,
        val returned: String? = null,
    ) : PurchasesEvent

    /** An app license or purchase update the Store sent without being asked. */
    data class Delivered(
        val delivery: Delivery,
    ) : PurchasesEvent
}

object PurchasesReducer : Reducer<PurchasesState, PurchasesEvent> {
    override fun reduce(
        state: PurchasesState,
        event: PurchasesEvent,
    ): PurchasesState =
        when (event) {
            is PurchasesEvent.Ready -> state.copy(supported = event.supported)
            is PurchasesEvent.AvailabilityRead -> state.copy(availability = event.availability)
            is PurchasesEvent.LicenseRead -> state.copy(license = event.license)
            is PurchasesEvent.ProductIdsChanged -> state.copy(productIds = event.text)
            is PurchasesEvent.ProductsLoaded -> state.copy(products = event.products)
            is PurchasesEvent.PurchasesListed -> state.copy(purchases = event.purchases)
            is PurchasesEvent.Called ->
                state.copy(calls = state.calls.plusCall(event.call, event.outcome, event.returned))
            is PurchasesEvent.Delivered -> state.copy(updates = state.updates.plusDelivery(event.delivery))
        }
}
