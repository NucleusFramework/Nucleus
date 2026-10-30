package dev.nucleusframework.lab.probes.system.purchases

import androidx.lifecycle.ViewModel
import dev.nucleusframework.iap.PurchaseResult
import dev.nucleusframework.iap.StoreException
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.unjudgedDelivery
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class PurchasesViewModel(
    private val gateway: PurchasesGateway,
    commands: LabCommands,
    timeline: Timeline,
) : MviViewModel<PurchasesState, PurchasesIntent, PurchasesEvent, Nothing>(
        PurchasesState(),
        PurchasesReducer,
        timeline,
        PurchasesProbe.ID,
    ) {
    init {
        val supported = gateway.supported()
        dispatch(PurchasesEvent.Ready(supported))
        if (supported.isAvailable) {
            launch { refresh() }
            // Collected for the probe's whole life: a purchase or refund made from the Store's own
            // UI, or a trial converted there, must show up without any request.
            launch {
                gateway.appLicenseUpdates().collect { license ->
                    dispatch(PurchasesEvent.LicenseRead(license))
                    dispatch(PurchasesEvent.Delivered(unjudgedDelivery("app license → ${license.summary()}")))
                }
            }
            launch {
                gateway.purchaseUpdates().collect { update ->
                    dispatch(PurchasesEvent.Delivered(unjudgedDelivery(update.toString())))
                }
            }
            launch {
                gateway.diagnostics().collect { message ->
                    dispatch(PurchasesEvent.Delivered(unjudgedDelivery("store: $message")))
                }
            }
        }
        // nucleus-lab://probe/system.purchases?ids=pro,coins
        onParams(commands) { params ->
            params["ids"]?.let { onIntent(PurchasesIntent.SetProductIds(it)) }
        }
    }

    override suspend fun handle(intent: PurchasesIntent) {
        if (!state.value.supported.isAvailable && intent !is PurchasesIntent.SetProductIds) return
        when (intent) {
            PurchasesIntent.Refresh -> refresh()
            is PurchasesIntent.SetProductIds -> reduceSilently(PurchasesEvent.ProductIdsChanged(intent.text))
            PurchasesIntent.LoadProducts -> {
                val ids = parseProductIds(state.value.productIds)
                call("products($ids)") { gateway.products(ids) }
                    ?.let { products ->
                        dispatch(PurchasesEvent.ProductsLoaded(products))
                        // Asked for but not sold: a Partner Center id that does not match.
                        val missing = ids - products.map { it.id }.toSet()
                        if (missing.isNotEmpty()) {
                            dispatch(
                                PurchasesEvent.Called("products", CallOutcome(false, "not found: $missing")),
                                Severity.Warning,
                            )
                        }
                    }
            }
            PurchasesIntent.ListPurchases ->
                call("purchases()") { gateway.purchases() }?.let { dispatch(PurchasesEvent.PurchasesListed(it)) }
            PurchasesIntent.Restore ->
                call("restorePurchases()") { gateway.restorePurchases() }?.let {
                    dispatch(PurchasesEvent.PurchasesListed(it))
                }
            is PurchasesIntent.Entitlement ->
                call("entitlement(${intent.productId})") { gateway.entitlement(intent.productId) }
            is PurchasesIntent.Consume -> {
                call("consume(${intent.purchase.productId})") { gateway.consume(intent.purchase) } ?: return
                call("purchases()") { gateway.purchases() }?.let { dispatch(PurchasesEvent.PurchasesListed(it)) }
            }
            is PurchasesIntent.BuyApp -> {
                purchaseCall("purchaseApp()") { gateway.purchaseApp(intent.hwnd) }
                readLicense()
            }
            is PurchasesIntent.Buy ->
                purchaseCall("purchase(${intent.productId})") {
                    gateway.purchase(intent.productId, intent.hwnd)
                }
        }
    }

    private suspend fun refresh() {
        call("availability()") { gateway.availability() }?.let { dispatch(PurchasesEvent.AvailabilityRead(it)) }
        readLicense()
    }

    private suspend fun readLicense() {
        call("appLicense()", returned = { it.summary() }) { gateway.appLicense() }
            ?.let { dispatch(PurchasesEvent.LicenseRead(it)) }
    }

    /**
     * Runs [block] and records it; `null` when it threw. A broken native bridge (a linkage
     * error) is recorded the same way, so it never takes the probe down.
     */
    private suspend fun <T> call(
        name: String,
        returned: (T) -> String? = { it?.toString() },
        block: suspend () -> T,
    ): T? =
        try {
            block().also { dispatch(PurchasesEvent.Called(name, CallOutcome.Ok, returned(it))) }
        } catch (e: StoreException) {
            dispatch(PurchasesEvent.Called(name, CallOutcome(false, "${e.code}: ${e.message}")), Severity.Warning)
            null
        } catch (e: LinkageError) {
            dispatch(PurchasesEvent.Called(name, CallOutcome(false, e.summary)), Severity.Error)
            null
        }

    /** A purchase never throws a store failure: [PurchaseResult.Failed] is the failed call. */
    private suspend fun purchaseCall(
        name: String,
        block: suspend () -> PurchaseResult,
    ) {
        val result = call(name, block = block) ?: return
        if (result is PurchaseResult.Failed) {
            dispatch(
                PurchasesEvent.Called(name, CallOutcome(false, "${result.error.code}: ${result.error.message}")),
                Severity.Warning,
            )
        }
    }
}
