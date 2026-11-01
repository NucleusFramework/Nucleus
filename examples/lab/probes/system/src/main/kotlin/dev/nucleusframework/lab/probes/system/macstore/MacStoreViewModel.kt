package dev.nucleusframework.lab.probes.system.macstore

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.unjudgedDelivery
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.store.mac.PurchaseResult
import dev.nucleusframework.store.mac.PurchaseUpdate
import dev.nucleusframework.store.mac.StoreException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class MacStoreViewModel(
    private val gateway: MacStoreGateway,
    commands: LabCommands,
    timeline: Timeline,
) : MviViewModel<MacStoreState, MacStoreIntent, MacStoreEvent, Nothing>(
        MacStoreState(),
        MacStoreReducer,
        timeline,
        MacStoreProbe.ID,
    ) {
    init {
        val supported = gateway.supported()
        dispatch(MacStoreEvent.Ready(supported, supported.isAvailable && gateway.testing()))
        if (supported.isAvailable) {
            launch { refresh() }
            // Collected for the probe's whole life: renewals, refunds, purchases made elsewhere
            // and unconsumed consumables (replayed when collection starts) must show up unasked.
            launch {
                gateway.purchaseUpdates().collect { update ->
                    val kind = if (update is PurchaseUpdate.Revoked) "revoked" else "purchased"
                    dispatch(
                        MacStoreEvent.Delivered(
                            unjudgedDelivery("$kind ${update.purchase.productId} · ${update.purchase.summary()}"),
                        ),
                    )
                }
            }
        }
        // nucleus-lab://probe/system.macAppStore?ids=pro,coins
        onParams(commands) { params ->
            params["ids"]?.let { onIntent(MacStoreIntent.SetProductIds(it)) }
        }
    }

    override suspend fun handle(intent: MacStoreIntent) {
        val editsOnly = intent is MacStoreIntent.SetProductIds || intent is MacStoreIntent.SetActionProduct
        if (!state.value.supported.isAvailable && !editsOnly) return
        when (intent) {
            MacStoreIntent.Refresh -> refresh()
            is MacStoreIntent.SetProductIds -> reduceSilently(MacStoreEvent.ProductIdsChanged(intent.text))
            is MacStoreIntent.SetActionProduct -> reduceSilently(MacStoreEvent.ActionProductChanged(intent.productId))
            MacStoreIntent.LoadProducts -> loadProducts()
            MacStoreIntent.ListPurchases -> listPurchases()
            is MacStoreIntent.Entitlement ->
                call("entitlement(${intent.productId})") { gateway.entitlement(intent.productId) }
            is MacStoreIntent.Consume -> {
                call("consume(${intent.purchase.productId})") { gateway.consume(intent.purchase) } ?: return
                listPurchases()
            }
            is MacStoreIntent.Buy -> {
                purchaseCall("purchase(${intent.productId})") { gateway.purchase(intent.productId, intent.window) }
                listPurchases()
            }
            MacStoreIntent.ReadAppTransaction ->
                call(
                    "appTransaction()",
                    returned = { "${it.bundleId} · ${it.environment}" },
                ) { gateway.appTransaction() }
                    ?.let { dispatch(MacStoreEvent.AppTransactionRead(it)) }
            MacStoreIntent.OpenStorePage -> call("openStorePage()") { gateway.openStorePage() }
            is MacStoreIntent.Switch ->
                call(
                    "testing.${intent.switch.name} = ${intent.enabled}",
                ) { gateway.switch(intent.switch, intent.enabled) }
                    ?.let { dispatch(MacStoreEvent.Switched(intent.switch, intent.enabled)) }
            is MacStoreIntent.Act ->
                call(
                    "testing.${intent.action.name}(${intent.productId})",
                ) { gateway.act(intent.action, intent.productId) }
            MacStoreIntent.ClearTransactions -> {
                call("testing.clearTransactions()") { gateway.clearTransactions() } ?: return
                listPurchases()
            }
        }
    }

    private suspend fun refresh() {
        call("availability()") { gateway.availability() }?.let { dispatch(MacStoreEvent.AvailabilityRead(it)) }
        if (state.value.testing) {
            call("testing.settings()") { gateway.settings() }?.let { settings ->
                dispatch(MacStoreEvent.Switched(StoreSwitch.Sheets, settings.purchaseSheetsEnabled))
                dispatch(MacStoreEvent.Switched(StoreSwitch.AskToBuy, settings.askToBuyEnabled))
                dispatch(MacStoreEvent.Switched(StoreSwitch.BillingRetry, settings.billingRetryOnRenewal))
                dispatch(MacStoreEvent.Switched(StoreSwitch.GracePeriod, settings.billingGracePeriod))
            }
        }
        listPurchases()
    }

    private suspend fun loadProducts() {
        val ids = parseProductIds(state.value.productIds)
        val products =
            call("products($ids)", returned = { list ->
                list.map { it.id }.toString()
            }) { gateway.products(ids) }
        products ?: return
        dispatch(MacStoreEvent.ProductsLoaded(products))
        // Asked for but not sold: an id App Store Connect (or the .storekit file) does not know.
        val missing = ids - products.map { it.id }.toSet()
        if (missing.isNotEmpty()) {
            dispatch(MacStoreEvent.Called("products", CallOutcome(false, "not found: $missing")), Severity.Warning)
        }
    }

    private suspend fun listPurchases() {
        call("list()", returned = { list -> list.map { it.productId }.toString() }) { gateway.purchases() }
            ?.let { dispatch(MacStoreEvent.PurchasesListed(it)) }
    }

    /**
     * Runs [block] and records it; `null` when it threw. A broken native bridge (a linkage
     * error) is recorded the same way, so it never takes the probe down.
     */
    private suspend fun <T> call(
        name: String,
        returned: (T) -> String? = { result -> result?.takeUnless { it is Unit }?.toString() },
        block: suspend () -> T,
    ): T? =
        try {
            block().also { dispatch(MacStoreEvent.Called(name, CallOutcome.Ok, returned(it))) }
        } catch (e: StoreException) {
            dispatch(MacStoreEvent.Called(name, CallOutcome(false, "${e.code}: ${e.message}")), Severity.Warning)
            null
        } catch (e: LinkageError) {
            dispatch(MacStoreEvent.Called(name, CallOutcome(false, e.summary)), Severity.Error)
            null
        }

    /** A purchase never throws a store failure: [PurchaseResult.Failed] is the failed call. */
    private suspend fun purchaseCall(
        name: String,
        block: suspend () -> PurchaseResult,
    ) {
        val result = call(name, returned = { it.summary() }, block = block) ?: return
        if (result is PurchaseResult.Failed) {
            dispatch(
                MacStoreEvent.Called(name, CallOutcome(false, "${result.error.code}: ${result.error.message}")),
                Severity.Warning,
            )
        }
    }
}

private fun PurchaseResult.summary(): String =
    when (this) {
        is PurchaseResult.Success -> "Success · #${purchase.transactionId} · ${purchase.environment}"
        PurchaseResult.Pending -> "Pending"
        PurchaseResult.Cancelled -> "Cancelled"
        is PurchaseResult.Failed -> "Failed · ${error.code}"
    }
