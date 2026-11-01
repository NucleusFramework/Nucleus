package dev.nucleusframework.lab.probes.system.macstore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.Readouts
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubSection
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.store.mac.AppTransactionInfo
import dev.nucleusframework.store.mac.ProductType
import dev.nucleusframework.store.mac.StoreAvailability
import dev.nucleusframework.store.mac.StoreProduct
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class MacStoreProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Mac App Store purchases",
            domain = Domain.System,
            summary =
                "Does StoreKit 2 sell the Lab's products, report what the customer owns, and tell the " +
                    "app about every renewal, refund and purchase made elsewhere?",
            modules = listOf("mac-app-store"),
            platforms = setOf(Platform.MacOS),
            checks =
                listOf(
                    Check(
                        "availability",
                        "runStoreKit: Available with StoreKit Testing on; gradle run (bare java): NotBundled",
                    ),
                    Check(
                        "products",
                        "Load products lists pro, coins and premium.monthly with price, type and the free week",
                    ),
                    Check(
                        "buy",
                        "Buy pro: the sheet opens over the Lab window; once confirmed, OK on \"You're all set\", " +
                            "the call ends Success and pro is listed",
                    ),
                    Check("cancel", "Cancel in the sheet ends Cancelled, not Failed"),
                    Check(
                        "consumable",
                        "A bought consumable stays listed, across a restart of the Lab, until Consume removes it",
                    ),
                    Check(
                        "subscription",
                        "Once subscribed, premium.monthly's entitlement is Active with an end date and auto-renew",
                    ),
                    Check(
                        "store-side",
                        "Refund, Renew now and Buy outside the app each arrive under Updates without any other click",
                    ),
                    Check(
                        "billing",
                        "Renewals fail on billing + Renew now: entitlement InBillingRetry (InGracePeriod with the " +
                            "grace switch), Active again after Resolve billing issue",
                    ),
                    Check(
                        "ask-to-buy",
                        "Ask to Buy on: Buy ends Pending; Approve Ask to Buy delivers the purchase as an update",
                    ),
                    Check("app", "App transaction reads the Lab's bundle id in the Xcode environment"),
                ),
            keywords =
                listOf(
                    "in-app purchase",
                    "IAP",
                    "Mac App Store",
                    "StoreKit",
                    "StoreKit Testing",
                    "subscription",
                    "AppTransaction",
                ),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<MacStoreViewModel>()
        val state by vm.state.collectAsState()
        val window =
            LocalNucleusWindow.current.unsafe.taoWindow
                ?.nativeHandle ?: 0L
        val ready = state.supported.isAvailable

        ProbeLayout(
            capabilities =
                listOf(
                    Capability("StoreKit bridge", state.supported, detail = "StoreKit 2"),
                    Capability("Store", state.availability.toAvailability(), detail = "needs an app bundle"),
                    Capability(
                        "StoreKit Testing",
                        if (state.testing) Availability.Available else Availability.Unavailable("no .storekit"),
                        detail = "./gradlew :examples:lab:app:runStoreKit",
                    ),
                ),
            controls = {
                SubSection("Products") {
                    TextFieldRow("Product ids", state.productIds) { vm.onIntent(MacStoreIntent.SetProductIds(it)) }
                    Actions {
                        SecondaryAction("Load products", enabled = ready) { vm.onIntent(MacStoreIntent.LoadProducts) }
                        SecondaryAction("Refresh", enabled = ready) { vm.onIntent(MacStoreIntent.Refresh) }
                    }
                    if (state.products.isEmpty()) EmptyState("No product loaded.")
                    state.products.forEach { product ->
                        Readout(product.id, product.describe())
                        Actions {
                            SecondaryAction("Buy ${product.id}") { vm.onIntent(MacStoreIntent.Buy(product.id, window)) }
                            SecondaryAction("Entitlement") { vm.onIntent(MacStoreIntent.Entitlement(product.id)) }
                        }
                    }
                }
                SubSection("Purchases") {
                    Actions {
                        SecondaryAction("List", enabled = ready) { vm.onIntent(MacStoreIntent.ListPurchases) }
                    }
                    if (state.purchases.isEmpty()) EmptyState("Nothing owned.")
                    state.purchases.forEach { purchase ->
                        Readout(purchase.productId, purchase.summary())
                        // Consume is not chained to Buy, so an unconsumed purchase can be seen coming back.
                        if (purchase.productType == ProductType.Consumable) {
                            Actions {
                                SecondaryAction("Consume ${purchase.productId}") {
                                    vm.onIntent(MacStoreIntent.Consume(purchase))
                                }
                            }
                        }
                    }
                }
                SubSection("The app") {
                    Actions {
                        SecondaryAction(
                            "App transaction",
                            enabled = ready,
                        ) { vm.onIntent(MacStoreIntent.ReadAppTransaction) }
                        SecondaryAction(
                            "Open store page",
                            enabled = ready,
                        ) { vm.onIntent(MacStoreIntent.OpenStorePage) }
                    }
                }
                SubSection("App Store side (StoreKit Testing)") {
                    if (!state.testing) {
                        Hint("Run the Lab with ./gradlew :examples:lab:app:runStoreKit to drive the store side.")
                    }
                    StoreSwitch.entries.forEach { switch ->
                        SwitchRow(switch.label, state.switches.getValue(switch), enabled = state.testing) {
                            vm.onIntent(MacStoreIntent.Switch(switch, it))
                        }
                    }
                    TextFieldRow("Product", state.actionProductId) { vm.onIntent(MacStoreIntent.SetActionProduct(it)) }
                    Actions {
                        StoreAction.entries.forEach { action ->
                            SecondaryAction(action.label, enabled = state.testing) {
                                vm.onIntent(MacStoreIntent.Act(action, state.actionProductId.trim()))
                            }
                        }
                    }
                    Actions {
                        SecondaryAction("Clear all transactions", enabled = state.testing) {
                            vm.onIntent(MacStoreIntent.ClearTransactions)
                        }
                    }
                }
            },
            observed = {
                SubSection("App transaction") {
                    val app = state.app
                    if (app == null) EmptyState("Not read yet.") else Readouts(app.fields())
                }
                SubSection("Updates") {
                    Hint(
                        "Sent by the App Store without a request: renewals, refunds, purchases made elsewhere, " +
                            "approved Ask to Buy, and unconsumed consumables replayed when the probe opens.",
                    )
                    EventLog(state.updates, empty = "No update yet.")
                }
                SubSection("Calls") {
                    EventLog(state.calls)
                }
            },
        )
    }

    companion object {
        val ID = ProbeId("system.macAppStore")
    }
}

private fun StoreAvailability?.toAvailability(): Availability =
    when (this) {
        null -> Availability.Unknown
        StoreAvailability.Available -> Availability.Available
        else -> Availability.Unavailable(name)
    }

private fun StoreProduct.describe(): String =
    buildString {
        append("$title · $displayPrice · $type")
        subscription?.let { sub ->
            append(" · every ${sub.period.value} ${sub.period.unit}")
            sub.introductoryOffer?.let { append(" · ${it.paymentMode} ${it.period.value} ${it.period.unit}") }
        }
    }

private fun AppTransactionInfo.fields(): List<Pair<String, String?>> =
    listOf(
        "bundle id" to bundleId,
        "Apple ID" to appId?.toString(),
        "version" to appVersion,
        "original version" to originalAppVersion,
        "first got" to originalPurchaseTime?.toString(),
        "environment" to environment.name,
        "revoked" to revocationTime?.toString(),
    )
