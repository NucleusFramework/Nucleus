package dev.nucleusframework.store.mac.e2e

import dev.nucleusframework.store.mac.MacAppStore
import dev.nucleusframework.store.mac.OfferPaymentMode
import dev.nucleusframework.store.mac.PeriodUnit
import dev.nucleusframework.store.mac.ProductType
import dev.nucleusframework.store.mac.StoreAvailability
import dev.nucleusframework.store.mac.StoreErrorCode
import dev.nucleusframework.store.mac.StoreException
import dev.nucleusframework.store.mac.SubscriptionPeriod
import dev.nucleusframework.store.mac.internal.NativeMacStoreBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.system.exitProcess

/**
 * End-to-end check of `mac-app-store` against real StoreKit, driven by
 * `scripts/mac-app-store-e2e.sh`. Only the public API: what an app sees.
 *
 * Inside the development `.app` (with `-Dnucleus.macAppStore.storeKitConfiguration`) it checks
 * the products of `e2e/Products.storekit`; launched as a bare `java` (`expect=notBundled`) it
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
            if (args.contains("expect=notBundled")) {
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
