# Nucleus In-App Purchase

In-app purchases for JVM desktop apps installed from the Microsoft Store:

| Platform | Backend | Status |
|---|---|---|
| Windows | `Windows.Services.Store.StoreContext` | supported (MSIX/AppX installs) |
| macOS | — | not yet: every call fails with `StoreErrorCode.Unsupported` |
| Linux | — no OS store | every call fails with `StoreErrorCode.Unsupported` |

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.in-app-purchase:<version>")
}
```

## Usage

```kotlin
// Gate features at launch: local, cheap, works offline.
if (InAppPurchase.entitlement("pro").isActive) unlockPro()

// Collect for the life of the app, from launch: renewals, refunds, purchases made elsewhere.
scope.launch {
    InAppPurchase.purchaseUpdates.collect { update ->
        when (update) {
            is PurchaseUpdate.Purchased -> grant(update.purchase)
            is PurchaseUpdate.Revoked -> revoke(update.purchase)
        }
    }
}

val pro = InAppPurchase.product("pro") ?: return
when (val result = InAppPurchase.purchase(pro) { windows { ownerWindowHandle = window.nativeHandle } }) {
    is PurchaseResult.Success -> grant(result.purchase)
    PurchaseResult.Pending -> showAwaitingApproval() // the outcome arrives through purchaseUpdates
    PurchaseResult.Cancelled -> Unit
    is PurchaseResult.Failed -> showError(result.error)
}
```

- **Product ids** are your own: Partner Center's add-on *product id* (`InAppOfferToken`).
  Microsoft's `StoreId` is only exposed as `StoreProduct.platformId`.
- **Consumables** stay pending until `InAppPurchase.consume(purchase)` reports them delivered, and
  `purchases()` lists them again at next launch until then — grant first, consume second, and a
  crash in between loses nothing. The fulfilment tracking id is fixed per purchase, so retrying
  `consume` never fulfils twice.
- **Restore**: the Microsoft Store keeps licenses current itself; `restorePurchases()` equals
  `purchases()`.
- **Cancellation**: every call suspends without blocking a thread and may be called from any
  thread; cancelling the coroutine cancels the underlying store operation.
- **Nothing is invented**: the Microsoft Store has no transaction id, purchase date or signed
  receipt for add-ons, so those fields are `null` instead of guessed.
- `StoreProduct.price` is a `BigDecimal` read from the store's (locale-formatted) price string;
  `displayPrice` is what to show.

## Platform requirements

The Store only answers apps with package identity — build `TargetFormat.AppX`. An NSIS/MSI/portable
build or `./gradlew run` gets `StoreAvailability.NotPackaged`. The package identity must match the
reservation in Partner Center (`appx { identityName; publisher }`), and the Store needs the app
published (a private/hidden listing is enough) with its add-ons live; otherwise every Store call
fails with `StoreErrorCode.NotAssociated` (`0x803F6107`). The purchase dialog needs an owner
window: pass `ownerWindowHandle` (`TaoWindow.nativeHandle`), or let the module pick this app's
foreground window.

## Testing

This is the one module CI cannot sign off on: a real transaction needs a Partner Center listing.
The unit tests drive the protocol with a fake transport; `WindowsNativeTortureTest` drives the
real DLL and the real `Windows.Services.Store` API as far as an app without a listing gets.
