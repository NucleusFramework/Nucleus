package dev.nucleusframework.lab.probes.system.purchases

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.iap.AppLicense
import dev.nucleusframework.iap.ProductType
import dev.nucleusframework.iap.StoreAvailability
import dev.nucleusframework.iap.StoreProduct
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
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.Readouts
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubSection
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class PurchasesProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Store purchases",
            domain = Domain.System,
            summary =
                "Does the Microsoft Store report the Lab's own license and trial, sell its add-ons, " +
                    "and tell the app about every purchase, consumption and refund?",
            modules = listOf("in-app-purchase"),
            platforms = setOf(Platform.Windows),
            checks =
                listOf(
                    Check(
                        "availability",
                        "Installed from the Store: Available; NSIS, MSI, portable or gradle run: NotPackaged",
                    ),
                    Check(
                        "unlicensed",
                        "A sideloaded or development MSIX reads \"not licensed by the Store\", never a full license",
                    ),
                    Check(
                        "trial",
                        "Installed as a trial: trial and active, with the time left Partner Center gave the trial",
                    ),
                    Check(
                        "buy-app",
                        "Buy full version: the Store dialog opens over the Lab; once paid the license reads " +
                            "full license and an app license update arrives",
                    ),
                    Check("products", "Load products lists every add-on with its Partner Center title, price and type"),
                    Check(
                        "durable",
                        "Buying pro succeeds; buying it again reports alreadyOwned; its entitlement is Active",
                    ),
                    Check(
                        "consumable",
                        "A bought consumable stays listed, across a restart of the Lab, until Consume removes it",
                    ),
                    Check(
                        "subscription",
                        "The subscription shows its period and trial; once bought its entitlement is " +
                            "Active with an end date",
                    ),
                    Check("cancel", "Closing the purchase dialog ends Cancelled, not Failed"),
                    Check(
                        "refund",
                        "A refund or a purchase made from the Store app arrives as a purchase update without any click",
                    ),
                ),
            keywords =
                listOf(
                    "in-app purchase",
                    "IAP",
                    "Microsoft Store",
                    "trial",
                    "license",
                    "add-on",
                    "StoreContext",
                ),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<PurchasesViewModel>()
        val state by vm.state.collectAsState()
        val hwnd =
            LocalNucleusWindow.current.unsafe.taoWindow
                ?.nativeHandle ?: 0L
        val ready = state.supported.isAvailable

        ProbeLayout(
            capabilities =
                listOf(
                    Capability("Store bridge", state.supported, detail = "Windows.Services.Store"),
                    Capability("Store", state.availability.toAvailability(), detail = "needs a Store-installed MSIX"),
                ),
            controls = {
                SubSection("App license") {
                    Actions {
                        PrimaryAction("Buy full version", enabled = ready) { vm.onIntent(PurchasesIntent.BuyApp(hwnd)) }
                        SecondaryAction("Refresh", enabled = ready) { vm.onIntent(PurchasesIntent.Refresh) }
                    }
                }
                SubSection("Add-ons") {
                    TextFieldRow("Product ids", state.productIds) { vm.onIntent(PurchasesIntent.SetProductIds(it)) }
                    Actions {
                        SecondaryAction("Load products", enabled = ready) { vm.onIntent(PurchasesIntent.LoadProducts) }
                    }
                    if (state.products.isEmpty()) EmptyState("No product loaded.")
                    state.products.forEach { product ->
                        Readout(product.id, product.describe())
                        Actions {
                            SecondaryAction("Buy ${product.id}") { vm.onIntent(PurchasesIntent.Buy(product.id, hwnd)) }
                            SecondaryAction("Entitlement") { vm.onIntent(PurchasesIntent.Entitlement(product.id)) }
                        }
                    }
                }
                SubSection("Purchases") {
                    Actions {
                        SecondaryAction("List", enabled = ready) { vm.onIntent(PurchasesIntent.ListPurchases) }
                        SecondaryAction("Restore", enabled = ready) { vm.onIntent(PurchasesIntent.Restore) }
                    }
                    if (state.purchases.isEmpty()) EmptyState("Nothing listed.")
                    state.purchases.forEach { purchase ->
                        Readout(
                            purchase.productId,
                            "${purchase.productType} · ${purchase.state}" +
                                (purchase.expirationTime?.let { " · until $it" } ?: ""),
                        )
                        // Consume is not chained to Buy, so an unconsumed purchase can be seen coming back.
                        if (purchase.productType == ProductType.Consumable) {
                            Actions {
                                SecondaryAction("Consume ${purchase.productId}") {
                                    vm.onIntent(PurchasesIntent.Consume(purchase))
                                }
                            }
                        }
                    }
                }
            },
            observed = {
                SubSection("App license") {
                    val license = state.license
                    if (license == null) {
                        EmptyState("Not read yet.")
                    } else {
                        Readout("Reads as", license.summary(), tone = license.tone())
                        Readouts(license.fields())
                    }
                }
                SubSection("Updates") {
                    Hint("Sent by the Store without a request: trial conversions, purchases made elsewhere, refunds.")
                    EventLog(state.updates, empty = "No update yet.")
                }
                SubSection("Calls") {
                    EventLog(state.calls)
                }
            },
        )
    }

    companion object {
        val ID = ProbeId("system.purchases")
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
        platformId?.let { append(" · $it") }
    }

private fun AppLicense.tone(): Tone =
    when {
        isFullLicense -> Tone.Ok
        isTrial && isActive -> Tone.Neutral
        else -> Tone.Warning
    }

private fun AppLicense.fields(): List<Pair<String, String?>> =
    listOf(
        "active" to isActive.toString(),
        "trial" to isTrial.toString(),
        "trial time left" to trialTimeRemaining?.toString(),
        "expires" to expirationTime?.toString(),
        "trial owned by this user" to isTrialOwnedByThisUser.toString(),
        "trial unique id" to trialUniqueId,
        "SKU" to skuStoreId,
    )
