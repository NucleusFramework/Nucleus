package dev.nucleusframework.store.mac.e2e

import dev.nucleusframework.store.mac.EntitlementState
import dev.nucleusframework.store.mac.MacAppStore
import dev.nucleusframework.store.mac.OfferPaymentMode
import dev.nucleusframework.store.mac.PeriodUnit
import dev.nucleusframework.store.mac.ProductType
import dev.nucleusframework.store.mac.PurchaseResult
import dev.nucleusframework.store.mac.PurchaseUpdate
import dev.nucleusframework.store.mac.RenewalRate
import dev.nucleusframework.store.mac.StoreAvailability
import dev.nucleusframework.store.mac.StoreEnvironment
import dev.nucleusframework.store.mac.StoreErrorCode
import dev.nucleusframework.store.mac.StoreException
import dev.nucleusframework.store.mac.SubscriptionPeriod
import dev.nucleusframework.store.mac.internal.NativeMacStoreBridge
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
            } else if (args.contains("crash-buy")) {
                crashBuy()
            } else if (args.contains("crash-relaunch")) {
                crashRelaunch(args.last())
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
        MacAppStore.testing.clearTransactions()
        MacAppStore.testing.setPurchaseSheetsEnabled(false)

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
            updates()
            appRecord()
            storeSide()
        } finally {
            frame.dispose()
        }
    }

    private suspend fun appRecord() {
        val app = runCatching { MacAppStore.appTransaction() }
        val info = app.getOrNull()
        check("app transaction", info?.bundleId == "dev.nucleusframework.macappstore.e2e") { app }
        check("app transaction from StoreKit Testing", info?.environment == StoreEnvironment.Xcode) { info }
        check(
            "app transaction is signed",
            info?.signedAppTransaction?.count { it == '.' } == 2,
        ) { info?.signedAppTransaction }
        check("no Apple ID in StoreKit Testing", info?.appId == null) { info?.appId }
        // Without an id there is nothing to open: it must fail rather than open the App Store.
        val page = runCatching { MacAppStore.openStorePage() }.exceptionOrNull()
        check("store page needs an id", (page as? StoreException)?.code == StoreErrorCode.InvalidArgument) { page }
    }

    /** The public StoreKit Testing controls, each seen through the API an app uses. */
    private suspend fun storeSide() =
        coroutineScope {
            val testing = MacAppStore.testing
            val purchases = MacAppStore.purchases
            check("testing is active", testing.isActive)
            testing.clearTransactions()

            val received = Channel<PurchaseUpdate>(Channel.UNLIMITED)
            val collector = launch { purchases.purchaseUpdates.collect { received.send(it) } }

            suspend fun next(productId: String): PurchaseUpdate? =
                withTimeoutOrNull(15_000) {
                    var update = received.receive()
                    while (update.purchase.productId != productId) update = received.receive()
                    update
                }

            testing.setAskToBuyEnabled(true)
            val settings = testing.settings()
            check("settings read back", settings.askToBuyEnabled && !settings.purchaseSheetsEnabled) { settings }
            val asked = purchases.purchase("pro")
            check("Ask to Buy makes the purchase pending", asked == PurchaseResult.Pending) { asked }
            testing.approveAskToBuy("pro")
            val approved = next("pro")
            check("approval arrives as Purchased", approved is PurchaseUpdate.Purchased) { approved }
            check("approved product entitled", purchases.entitlement("pro").isActive)

            testing.setAskToBuyEnabled(false)

            val subscribed = purchases.purchase("premium.monthly")
            check("subscribed for the billing checks", subscribed is PurchaseResult.Success) { subscribed }
            testing.setBillingRetryOnRenewal(true)
            testing.setBillingGracePeriod(true)
            testing.forceRenewal("premium.monthly")
            val grace = waitForState("premium.monthly", EntitlementState.InGracePeriod)
            check(
                "failed renewal with grace period keeps access",
                grace.state == EntitlementState.InGracePeriod && grace.isActive,
            ) { grace }
            testing.resolveBillingIssue("premium.monthly")
            val resolved = waitForState("premium.monthly", EntitlementState.Active)
            check("resolved billing issue is active again", resolved.state == EntitlementState.Active) { resolved }

            testing.setBillingGracePeriod(false)
            testing.forceRenewal("premium.monthly")
            val retry = waitForState("premium.monthly", EntitlementState.InBillingRetry)
            check(
                "failed renewal without grace loses access",
                retry.state == EntitlementState.InBillingRetry && !retry.isActive,
            ) { retry }
            testing.resolveBillingIssue("premium.monthly")
            testing.setBillingRetryOnRenewal(false)
            waitForState("premium.monthly", EntitlementState.Active)

            testing.setAutoRenew("premium.monthly", false)
            val noRenew = waitFor("premium.monthly") { it.willAutoRenew == false }
            check("auto-renew turned off", noRenew.willAutoRenew == false) { noRenew }

            testing.expireSubscription("premium.monthly")
            val expired = waitForState("premium.monthly", EntitlementState.Expired)
            check("expired subscription", expired.state == EntitlementState.Expired) { expired }

            testing.setStorefront("FRA")
            val french = purchases.product("pro")
            check("storefront drives the currency", french?.currencyCode == "EUR") {
                french?.let { "${it.currencyCode} ${it.displayPrice}" }
            }
            testing.setStorefront("USA")

            testing.setRenewalRate(RenewalRate.OneRenewalEveryTenSeconds)
            testing.setRenewalRate(RenewalRate.RealTime)
            check("renewal rate accepted", true)

            // StoreKit itself refusing an action (no subscription to renew) must come back as an
            // error: its NSError was once over-released and crashed the process.
            val refused = runCatching { testing.forceRenewal("pro") }.exceptionOrNull()
            check(
                "StoreKit refusal is a Platform error",
                (refused as? StoreException)?.code == StoreErrorCode.Platform,
            ) { refused }
            // Twice: the refusal path of buyProductWithIdentifier: is the one that crashed.
            repeat(2) {
                val owned = runCatching { testing.buyOutsideApp("pro") }.exceptionOrNull()
                check(
                    "buying an owned product outside the app is refused",
                    (owned as? StoreException)?.code == StoreErrorCode.Platform,
                ) {
                    owned
                }
            }
            check("store still answers after a refusal", purchases.product("pro") != null)

            val unknown = runCatching { testing.refund("never.bought") }.exceptionOrNull()
            check(
                "acting on nothing fails clearly",
                (unknown as? StoreException)?.code == StoreErrorCode.Platform ||
                    (unknown as? StoreException)?.code == StoreErrorCode.InvalidArgument,
            ) {
                unknown
            }
            collector.cancel()
        }

    private suspend fun waitForState(
        productId: String,
        state: EntitlementState,
    ) = waitFor(productId) { it.state == state }

    // StoreKit Testing applies store-side changes asynchronously.
    private suspend fun waitFor(
        productId: String,
        condition: (dev.nucleusframework.store.mac.EntitlementStatus) -> Boolean,
    ): dev.nucleusframework.store.mac.EntitlementStatus {
        var status = MacAppStore.purchases.entitlement(productId)
        var waited = 0
        // Up to 30 s: an expiry takes StoreKit some 15 s to report (measured).
        while (!condition(status) && waited++ < 150) {
            delay(200)
            status = MacAppStore.purchases.entitlement(productId)
        }
        return status
    }

    /** First launch of the crash check: pays for coins and quits before delivering them. */
    private suspend fun crashBuy() {
        MacAppStore.testing.clearTransactions()
        MacAppStore.testing.setPurchaseSheetsEnabled(false)
        val frame =
            Frame("mac-app-store crash buy").apply {
                setSize(420, 240)
                isVisible = true
            }
        val coins = (MacAppStore.purchases.purchase("coins") as? PurchaseResult.Success)?.purchase
        frame.dispose()
        check("coins paid, then the app dies", coins != null)
        println("TRANSACTION ${coins?.transactionId}")
    }

    /** Second launch: the paid coins come back through purchaseUpdates, exactly once. */
    private suspend fun crashRelaunch(transactionId: String) =
        coroutineScope {
            val seen = Channel<PurchaseUpdate>(Channel.UNLIMITED)
            val collector = launch { MacAppStore.purchases.purchaseUpdates.collect { seen.send(it) } }
            delay(6000)
            collector.cancel()
            seen.close()
            val copies = seen.toList().filter { it.purchase.transactionId == transactionId }
            check(
                "undelivered coins replayed at the next launch, once",
                copies.size == 1 && copies[0] is PurchaseUpdate.Purchased,
            ) {
                copies
            }
            copies.firstOrNull()?.let { MacAppStore.purchases.consume(it.purchase) }
            val left = MacAppStore.purchases.list().filter { it.transactionId == transactionId }
            check("consumed after the replay", left.isEmpty()) { left }
        }

    /** Store-side events of StoreKit Testing, the way the App Store sends them. */
    private suspend fun updates() =
        coroutineScope {
            val purchases = MacAppStore.purchases
            // Left unconsumed on purpose: StoreKit replays it when the app starts listening.
            val coins = (purchases.purchase("coins") as? PurchaseResult.Success)?.purchase
            check("coins bought for the replay", coins != null)

            val received = Channel<PurchaseUpdate>(Channel.UNLIMITED)
            val collector = launch { purchases.purchaseUpdates.collect { received.send(it) } }

            suspend fun next(what: (PurchaseUpdate) -> Boolean): PurchaseUpdate? =
                withTimeoutOrNull(15_000) {
                    var update = received.receive()
                    while (!what(update)) update = received.receive()
                    update
                }

            val replayed = next { it.purchase.transactionId == coins?.transactionId }
            check(
                "unconsumed consumable replayed when listening starts",
                replayed is PurchaseUpdate.Purchased,
            ) { replayed }

            MacAppStore.testing.refund("pro")
            val refunded = next { it.purchase.productId == "pro" }
            check("refund arrives as Revoked", refunded is PurchaseUpdate.Revoked) { refunded }
            val afterRefund = purchases.entitlement("pro")
            check("refunded product is revoked", afterRefund.state == EntitlementState.Revoked) { afterRefund }

            val before = purchases.entitlement("premium.monthly").purchase?.transactionId
            MacAppStore.testing.forceRenewal("premium.monthly")
            val renewed = next { it.purchase.productId == "premium.monthly" }
            check(
                "renewal arrives as Purchased with a new transaction",
                renewed is PurchaseUpdate.Purchased && renewed.purchase.transactionId != before,
            ) { renewed }

            MacAppStore.testing.buyOutsideApp("pro")
            val outside = next { it.purchase.productId == "pro" }
            check(
                "a purchase made outside the app arrives as Purchased",
                outside is PurchaseUpdate.Purchased,
            ) { outside }
            val rebought = purchases.entitlement("pro")
            check("bought again outside the app", rebought.isActive) { rebought }

            // What purchase() returns is not repeated in the flow.
            val direct = (purchases.purchase("coins") as? PurchaseResult.Success)?.purchase
            val echo = withTimeoutOrNull(3_000) { next { it.purchase.transactionId == direct?.transactionId } }
            check("purchase() results are not echoed as updates", direct != null && echo == null) { echo }

            // Store-side events must not swallow consumables the app has not consumed yet.
            val stillOwed = purchases.list().map { it.transactionId }
            check(
                "unconsumed consumables survive store-side events",
                coins?.transactionId in stillOwed && direct?.transactionId in stillOwed,
            ) { "owed ${coins?.transactionId}, ${direct?.transactionId}; listed $stillOwed" }
            purchases.list().filter { it.productType == ProductType.Consumable }.forEach { purchases.consume(it) }
            collector.cancel()
        }

    /**
     * `--sheet` mode of the script: shows the real purchase sheet, cancels the coroutine waiting
     * on it, and lets the script press the sheet's Cancel button through accessibility, as a
     * customer would. StoreKit ignores task cancellation and keeps its sheet up; closing it from
     * the app hangs StoreKit for good, so the customer's answer is the only way out.
     */
    private suspend fun sheetCancel() =
        kotlinx.coroutines.coroutineScope {
            MacAppStore.testing.clearTransactions()
            MacAppStore.testing.setPurchaseSheetsEnabled(true)
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
                MacAppStore.testing.setPurchaseSheetsEnabled(false)
                val again = kotlinx.coroutines.withTimeoutOrNull(20_000) { MacAppStore.purchases.purchase("pro") }
                check("the next purchase works", again is PurchaseResult.Success) { again }

                // Cancelled again, but this time the customer buys: the purchase must reach the
                // app through purchaseUpdates, since nobody waits for purchase() any more.
                val received = Channel<PurchaseUpdate>(Channel.UNLIMITED)
                val collector = launch { MacAppStore.purchases.purchaseUpdates.collect { received.send(it) } }
                MacAppStore.testing.setPurchaseSheetsEnabled(true)
                val late = launch { MacAppStore.purchases.purchase("coins") }
                delay(5000)
                late.cancel()
                late.join()
                println("STAGE press-purchase")
                val update =
                    withTimeoutOrNull(60_000) {
                        var next = received.receive()
                        while (next.purchase.productId != "coins") next = received.receive()
                        next
                    }
                check(
                    "a purchase made after its caller cancelled arrives as an update",
                    update is PurchaseUpdate.Purchased,
                ) { update }
                update?.let { MacAppStore.purchases.consume(it.purchase) }
                collector.cancel()
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
