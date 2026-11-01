# Nucleus Mac App Store

The Mac App Store (StoreKit 2) for JVM desktop apps it sells, behind one `MacAppStore` object.

- `MacAppStore.purchases` — in-app products: buy them, what the customer owns, consumables,
  and the changes the App Store sends (`purchaseUpdates`)
- `MacAppStore.appTransaction()` — the signed record of the customer getting the app itself
- `MacAppStore.openStorePage()` — the app's page in the App Store app

macOS 12+ only, and only from an app bundle. Elsewhere every call fails with
`StoreErrorCode.Unsupported` (Windows, Linux) or `StoreErrorCode.NotBundled` (a bare `java`
launch, `./gradlew run`).

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.mac-app-store:<version>")
}
```

## Usage

```kotlin
when (MacAppStore.availability()) {
    StoreAvailability.Available -> Unit
    StoreAvailability.PaymentsRestricted -> hideStore() // parental controls, MDM
    StoreAvailability.NotBundled, StoreAvailability.Unsupported -> return
}

val pro = MacAppStore.purchases.product("pro") ?: return
showBuyButton(pro.displayPrice)

val subscription = MacAppStore.purchases.product("premium.monthly")?.subscription
subscription?.introductoryOffer?.let { showTrial(it.period) } // only when this customer can still redeem it
```

### Buying

```kotlin
// Gate features at launch.
if (MacAppStore.purchases.entitlement("pro").isActive) unlockPro()

when (val result = MacAppStore.purchases.purchase("pro") { window = taoWindow.nativeHandle }) {
    is PurchaseResult.Success -> unlockPro()
    PurchaseResult.Pending -> showAwaitingApproval() // Ask to Buy
    PurchaseResult.Cancelled -> Unit
    is PurchaseResult.Failed -> showError(result.error)
}

// Consumables: grant first, consume second; a crash in between loses nothing.
MacAppStore.purchases.list().filter { it.productType == ProductType.Consumable }.forEach {
    grantCoins(it.quantity)
    MacAppStore.purchases.consume(it)
}
```

- The purchase sheet attaches to `window` (`TaoWindow.nativeHandle`), else to the app's key
  window. After a purchase macOS shows its own "You're all set" confirmation; `purchase()`
  returns once the customer closes it.
- **Cancelling `purchase()` does not close Apple's sheet** — StoreKit offers no way to, and
  closing it from the app leaves StoreKit stuck. The sheet stays the customer's: a cancel ends
  it, a purchase made there is recorded (it shows up in `entitlement()` and `list()`).
### Updates

```kotlin
// Collect for the life of the app, from launch.
scope.launch {
    MacAppStore.purchases.purchaseUpdates.collect { update ->
        when (update) {
            is PurchaseUpdate.Purchased -> grant(update.purchase)
            is PurchaseUpdate.Revoked -> revoke(update.purchase)
        }
    }
}
```

- Renewals, refunds, purchases made on another device or in the App Store app, approved Ask to
  Buy, and purchases completed after their `purchase()` call was cancelled.
- When collection starts, every unfinished transaction is replayed first — a consumable not yet
  consumed, a purchase the app died before delivering — even on a listener started late (StoreKit
  itself only replays at launch). Each transaction is delivered once per process; a refund of it
  still comes through.
- What `purchase()` returns is not repeated. A subscription simply running out is no update:
  re-read `entitlement()`.

### The app itself

```kotlin
val app = MacAppStore.appTransaction() // macOS 13+
if (app.originalAppVersion.toInt() < FREEMIUM_BUILD) unlockEverything() // bought before it went free
MacAppStore.openStorePage(appId = 1234567890) // the Apple ID from App Store Connect
```

- `entitlement()` says why a product is not active: `Expired`, `Revoked`, `InBillingRetry`
  (`InGracePeriod` still counts as active).
- Only transactions StoreKit verified are reported; `Purchase.signedTransaction` is the JWS to
  check on a server with the App Store Server API.

### Products

- **Product ids** are the App Store Connect product ids.
- `products()` keeps the order asked for and leaves out ids the store does not sell.
- `StoreProduct.price` is a `BigDecimal` in `currencyCode`; `displayPrice` is what to show.
- **Cancellation**: every call suspends without blocking a thread and may be called from any
  thread; cancelling the coroutine cancels the StoreKit operation — except the purchase sheet,
  which stays the customer's (see above).

## Developing without App Store Connect

StoreKit Testing serves the products of a `.storekit` file (Xcode: File › New › StoreKit
Configuration File) in this process, with no Apple account, no certificate and no Xcode run:

```
-Dnucleus.macAppStore.storeKitConfiguration=/path/to/Products.storekit
```

The app must still be an app bundle, and StoreKit Testing only accepts one that is
"installed for development":

1. signed with the `com.apple.security.get-task-allow` entitlement — an ad-hoc signature is
   enough (`codesign --force --deep -s - --entitlements dev.entitlements MyApp.app`);
2. known to LaunchServices — opened once, or `lsregister -f MyApp.app`;
3. on a Mac with Xcode installed (StoreKitTest ships with it; `DEVELOPER_DIR` or
   `xcode-select -p` picks which one).

The bundle does not have to contain the code: point its launcher configuration
(`Contents/app/<App>.cfg`, `app.classpath=`) at the build outputs and a rebuild needs no
repackaging. `scripts/mac-app-store-e2e.sh` builds such a development app once and runs the
module's end-to-end checks in it.

StoreKit remembers the configuration per bundle id: later launches see the same products and
purchase history.

### Playing the App Store's side

`MacAppStore.testing` drives the StoreKit Testing session from the app (a debug menu, an
automated test) while a configuration is active:

```kotlin
val testing = MacAppStore.testing
if (testing.isActive) {
    testing.setPurchaseSheetsEnabled(false)    // purchases complete at once
    testing.refund("pro")                      // → PurchaseUpdate.Revoked
    testing.forceRenewal("premium.monthly")    // → PurchaseUpdate.Purchased
    testing.setBillingRetryOnRenewal(true)     // next renewal fails: InBillingRetry / InGracePeriod
    testing.setAskToBuyEnabled(true)           // purchase() → Pending, then approveAskToBuy()
    testing.buyOutsideApp("coins")             // as if bought on another device
    testing.setRenewalRate(RenewalRate.OneRenewalEveryTenSeconds)
}
```

- An expiry (`expireSubscription`) reaches `entitlement()` some 15 seconds later.
- There is no `declineAskToBuy`: StoreKit Testing reports a declined Ask to Buy to StoreKit 2 as
  a completed purchase.

## Platform requirements

A shipped app reaches the store when it is signed for the Mac App Store and its bundle id has
products in App Store Connect. A bare `java` launch has the JDK's bundle, not an app, and gets
`StoreAvailability.NotBundled`.

## Testing

The unit tests drive the protocol with a fake transport; `scripts/mac-app-store-e2e.sh` drives
the real dylib and real StoreKit (StoreKit Testing) from a development app: products, purchases
of every kind, consumables, entitlements. `--sheet` adds the real purchase sheet, answered
through accessibility.
