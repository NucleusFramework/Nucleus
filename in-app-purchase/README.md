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

### In-app products

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

### Paid app and free trial

Give the app a price and a free trial in Partner Center, then gate on its own license:

```kotlin
val license = InAppPurchase.appLicense()
when {
    license.isFullLicense -> unlockEverything()
    license.isTrial && license.isActive -> showTrialBanner(license.trialTimeRemaining)
    else -> showTrialOver() // offer InAppPurchase.purchaseApp()
}

scope.launch {
    InAppPurchase.appLicenseUpdates.collect { if (it.isFullLicense) unlockEverything() }
}
```

- **The Store does not end a trial for you**: when it runs out, `isActive` turns `false` and the
  app locks what it decides to. Trial time running down is not an update: re-read `appLicense()`
  at launch, or schedule on `trialTimeRemaining`.
- **An install the Store did not license** (sideloaded, a development build, a package without
  a listing) reads as active and not a trial, with no `skuStoreId`. `isFullLicense` is `false`
  for it; do not gate on `isActive && !isTrial`.
- A time-limited trial (the Partner Center "free trial" of N days) is tied to the customer's
  Microsoft account by the Store itself: reinstalling does not restart it. `trialUniqueId` and
  `isTrialOwnedByThisUser` only describe usage-limited trials and stay `null` / `false` for it.
- `purchaseApp()` shows the Store's dialog for the app itself; its result works like
  `purchase()`, with the app's `StoreId` as `productId`.
- **Never sold twice from here**: the Microsoft Store was seen offering an owned durable add-on,
  and the owned app itself, for sale again. `purchase()` and `purchaseApp()` therefore check this
  device's licenses first and return `Success(alreadyOwned = true)` without any window. A device
  whose licenses have not synced yet still gets the Store's dialog: hide the buy button while
  `entitlement()` is active (or `appLicense().isFullLicense`), as Microsoft recommends.

### Add-ons

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

### App updates

The Store updates apps on its own, in the background. `StoreUpdates` is for not waiting for it, and
for updates Partner Center marks mandatory:

```kotlin
when (val check = StoreUpdates.check()) {
    is StoreUpdateCheck.Available -> {
        if (check.isMandatory) blockUntilUpdated()
        StoreUpdates.install { windows { ownerWindowHandle = window.nativeHandle } }
            .collect { showProgress(it.state, it.progress) }
    }
    StoreUpdateCheck.UpToDate -> Unit
}
```

- `install()` shows the Store's dialog over the app; `install { silent = true }` shows nothing, and
  Windows may refuse it (policy, metered connection) — the flow then fails instead of asking.
  Windows closes the app to install the new version.
- The flow ends with `Completed`, `UpToDate` (nothing to install) or `Cancelled` (the user declined
  the dialog); it fails with `StoreErrorCode.UpdateBlocked` while the Store waits for a charger or
  Wi-Fi. Cancelling the collection cancels the download.
- `StoreUpdates.openStorePage()` opens the app's page in the Store app instead.

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
