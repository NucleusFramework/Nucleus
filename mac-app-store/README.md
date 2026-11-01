# Nucleus Mac App Store

The Mac App Store (StoreKit 2) for JVM desktop apps it sells, behind one `MacAppStore` object.

- `MacAppStore.purchases` — in-app products: buy them, what the customer owns, consumables

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

## Platform requirements

A shipped app reaches the store when it is signed for the Mac App Store and its bundle id has
products in App Store Connect. A bare `java` launch has the JDK's bundle, not an app, and gets
`StoreAvailability.NotBundled`.

## Testing

The unit tests drive the protocol with a fake transport; `scripts/mac-app-store-e2e.sh` drives
the real dylib and real StoreKit (StoreKit Testing) from a development app: products, purchases
of every kind, consumables, entitlements. `--sheet` adds the real purchase sheet, answered
through accessibility.
