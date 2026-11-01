# Nucleus Mac App Store

The Mac App Store (StoreKit 2) for JVM desktop apps it sells, behind one `MacAppStore` object.

- `MacAppStore.purchases` — the app's in-app products

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

- **Product ids** are the App Store Connect product ids.
- `products()` keeps the order asked for and leaves out ids the store does not sell.
- `StoreProduct.price` is a `BigDecimal` in `currencyCode`; `displayPrice` is what to show.
- **Cancellation**: every call suspends without blocking a thread and may be called from any
  thread; cancelling the coroutine cancels the StoreKit operation.

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
the real dylib and real StoreKit (StoreKit Testing) from a development app.
