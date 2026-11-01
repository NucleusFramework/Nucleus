package dev.nucleusframework.store.mac.e2e

import dev.nucleusframework.store.mac.EntitlementState
import dev.nucleusframework.store.mac.MacAppStore
import dev.nucleusframework.store.mac.OfferPaymentMode
import dev.nucleusframework.store.mac.PeriodUnit
import dev.nucleusframework.store.mac.ProductType
import dev.nucleusframework.store.mac.PurchaseResult
import dev.nucleusframework.store.mac.StoreAvailability
import dev.nucleusframework.store.mac.StoreEnvironment
import dev.nucleusframework.store.mac.StoreErrorCode
import dev.nucleusframework.store.mac.StoreException
import dev.nucleusframework.store.mac.SubscriptionPeriod
import dev.nucleusframework.store.mac.internal.NativeMacStoreBridge
import dev.nucleusframework.store.mac.internal.NativeStoreBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.Frame
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.system.exitProcess

/**
 * End-to-end check of `mac-app-store` against real StoreKit, driven by
 * `scripts/mac-app-store-e2e.sh`. Only the public API: what an app sees.
 *
 * Inside the development `.app` (with `-Dnucleus.macAppStore.storeKitConfiguration`) it checks
 * the products of `e2e/Products.storekit` and buys them, with the purchase sheets turned off
 * through the StoreKit Testing session; launched as a bare `java` (`expect=notBundled`) it
 * checks that StoreKit is reported unreachable.
 */
object MacAppStoreE2E {
    private var failures = 0

    private fun check(
        name: String,
        condition: Boolean,
        detail: () -> Any? = { null },
    ) {
        if (condition) {
            println("PASS $name")
        } else {
            failures++
            println("FAIL $name ${detail() ?: ""}")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking {
            check("bridge loaded", MacAppStore.isSupported)
            val availability = MacAppStore.availability()
            if (args.contains("sheet")) {
                sheetCancel()
            } else if (args.contains("expect=notBundled")) {
                check("bare java is NotBundled", availability == StoreAvailability.NotBundled) { availability }
                val error = runCatching { MacAppStore.purchases.products("pro") }.exceptionOrNull()
                check(
                    "bare java store calls fail fast",
                    (error as? StoreException)?.code == StoreErrorCode.NotBundled,
                ) { error }
            } else {
                storeKitConfiguration(availability)
            }
        }
        println("DONE failures=$failures")
        exitProcess(failures)
    }

    private suspend fun storeKitConfiguration(availability: StoreAvailability) {
        check("available", availability == StoreAvailability.Available) { availability }
        // A fresh customer every run: the intro offer and NotOwned checks depend on it.
        val session = MacAppStore.backend as NativeStoreBackend
        session.devCommand("dev.clearTransactions")
        session.devCommand("dev.setDialogs", """{"enabled":false}""")

        val products = MacAppStore.purchases.products("premium.monthly", "nope", "pro", "coins", "pro")
        check(
            "order kept, unknown and repeated ids dropped",
            products.map { it.id } == listOf("premium.monthly", "pro", "coins"),
        ) {
            products.map { it.id }
        }

        val pro = products.first { it.id == "pro" }
        check("non-consumable", pro.type == ProductType.NonConsumable) { pro.type }
        check("title outside the BMP survives JNI", pro.title == "Pro 🚀") { pro.title }
        check("price", pro.price == BigDecimal("4.99")) { pro.price }
        check("currency", pro.currencyCode == "USD") { pro.currencyCode }
        check("display price", pro.displayPrice.contains("4.99")) { pro.displayPrice }
        check("no subscription on a non-consumable", pro.subscription == null)

        val coins = products.first { it.id == "coins" }
        check("consumable", coins.type == ProductType.Consumable) { coins.type }

        val monthly = products.first { it.id == "premium.monthly" }
        val subscription = monthly.subscription
        check("auto-renewable", monthly.type == ProductType.AutoRenewableSubscription) { monthly.type }
        check(
            "monthly period",
            subscription?.period == SubscriptionPeriod(1, PeriodUnit.Month),
        ) { subscription?.period }
        check("subscription group", subscription?.groupId == "21000001") { subscription?.groupId }
        val offer = subscription?.introductoryOffer
        check(
            "free week offered to a new customer",
            offer?.paymentMode == OfferPaymentMode.FreeTrial && offer.period.unit == PeriodUnit.Week,
        ) {
            offer
        }

        check("single product lookup", MacAppStore.purchases.product("coins")?.id == "coins")
        check("unknown product is null", MacAppStore.purchases.product("nope") == null)

        // Cancelled mid-flight: the call must neither hang nor leak, and the store keeps answering.
        coroutineCancellation()
        check("store answers after cancellations", MacAppStore.purchases.product("pro") != null)

        // The purchase sheet attaches to a window of the app: any will do in a test.
        val frame =
            Frame("mac-app-store E2E").apply {
                setSize(420, 240)
                isVisible = true
            }
        try {
            purchases()
        } finally {
            frame.dispose()
        }
    }

    /**
     * `--sheet` mode of the script: shows the real purchase sheet, cancels the coroutine waiting
     * on it, and lets the script press the sheet's Cancel button through accessibility, as a
     * customer would. StoreKit ignores task cancellation and keeps its sheet up; closing it from
     * the app hangs StoreKit for good, so the customer's answer is the only way out.
     */
    private suspend fun sheetCancel() =
        kotlinx.coroutines.coroutineScope {
            val session = MacAppStore.backend as NativeStoreBackend
            session.devCommand("dev.clearTransactions")
            session.devCommand("dev.setDialogs", """{"enabled":true}""")
            val frame =
                Frame("mac-app-store sheet probe").apply {
                    setSize(420, 240)
                    isVisible = true
                }
            try {
                val job = launch { MacAppStore.purchases.purchase("pro") }
                delay(5000)
                job.cancel()
                job.join()
                check("the cancelled caller returns at once", job.isCancelled)
                check(
                    "the sheet is still the customer's",
                    NativeMacStoreBridge.inFlight() == 1,
                ) { NativeMacStoreBridge.inFlight() }
                println("STAGE press-cancel")
                var waited = 0
                while (NativeMacStoreBridge.inFlight() > 0 && waited++ < 300) delay(100)
                check("the customer's cancel ends the StoreKit call", NativeMacStoreBridge.inFlight() == 0) {
                    NativeMacStoreBridge.inFlight()
                }
                session.devCommand("dev.setDialogs", """{"enabled":false}""")
                val again = kotlinx.coroutines.withTimeoutOrNull(20_000) { MacAppStore.purchases.purchase("pro") }
                check("the next purchase works", again is PurchaseResult.Success) { again }
            } finally {
                frame.dispose()
            }
        }

    private suspend fun purchases() {
        val purchases = MacAppStore.purchases
        val initial = purchases.list()
        check("nothing owned at first", initial.isEmpty()) { initial }
        val notOwned = purchases.entitlement("pro")
        check("pro not owned", notOwned.state == EntitlementState.NotOwned) { notOwned }

        val pro = purchases.purchase("pro")
        val proPurchase = (pro as? PurchaseResult.Success)?.purchase
        check("pro bought", proPurchase != null) { pro }
        check(
            "bought in the StoreKit Testing environment",
            proPurchase?.environment == StoreEnvironment.Xcode,
        ) { proPurchase }
        check(
            "signed transaction (JWS)",
            proPurchase?.signedTransaction?.count {
                it == '.'
            } == 2,
        ) { proPurchase?.signedTransaction }
        val proEntitlement = purchases.entitlement("pro")
        check(
            "pro entitled",
            proEntitlement.isActive && proEntitlement.purchase?.transactionId == proPurchase?.transactionId,
        ) {
            proEntitlement
        }

        val token = UUID.randomUUID()
        val coins = purchases.purchase("coins") { appAccountToken = token }
        val coinsPurchase = (coins as? PurchaseResult.Success)?.purchase
        check("coins bought", coinsPurchase?.productType == ProductType.Consumable) { coins }
        check(
            "app account token round trip",
            coinsPurchase?.appAccountToken == token,
        ) { coinsPurchase?.appAccountToken }
        val withCoins = purchases.list()
        check(
            "unconsumed coins listed",
            withCoins.any { it.transactionId == coinsPurchase?.transactionId },
        ) { withCoins }
        check("a consumable is never an entitlement", purchases.entitlement("coins").state == EntitlementState.NotOwned)
        coinsPurchase?.let { purchases.consume(it) }
        val afterConsume = purchases.list()
        check("consumed coins leave the list", afterConsume.none { it.productId == "coins" }) { afterConsume }
        val again = coinsPurchase?.let { runCatching { purchases.consume(it) }.exceptionOrNull() }
        check("consuming twice is harmless", again == null) { again }

        val monthly = purchases.purchase("premium.monthly")
        check("subscribed", monthly is PurchaseResult.Success) { monthly }
        val subscription = purchases.entitlement("premium.monthly")
        check("subscription active", subscription.state == EntitlementState.Active) { subscription }
        check("subscription renews", subscription.willAutoRenew == true) { subscription }
        check("subscription expires later", subscription.expirationTime?.isAfter(Instant.now()) == true) {
            subscription.expirationTime
        }

        val owned = purchases.list().map { it.productId }.sorted()
        check("list shows what is owned", owned == listOf("premium.monthly", "pro")) { owned }

        val unknown = purchases.purchase("nope")
        check(
            "unknown product fails as a result",
            (unknown as? PurchaseResult.Failed)?.error?.code == StoreErrorCode.ProductNotFound,
        ) {
            unknown
        }
    }

    private suspend fun coroutineCancellation() =
        kotlinx.coroutines.coroutineScope {
            repeat(20) {
                val job = launch { MacAppStore.purchases.products("pro", "coins") }
                delay(it % 3L)
                job.cancel()
                job.join()
            }
            check("20 cancelled lookups returned", true)
            // Every cancelled StoreKit task still answers and leaves the native registry.
            var waited = 0
            while (NativeMacStoreBridge.inFlight() > 0 && waited++ < 100) delay(20)
            check(
                "no native request left in flight",
                NativeMacStoreBridge.inFlight() == 0,
            ) { NativeMacStoreBridge.inFlight() }
        }
}
