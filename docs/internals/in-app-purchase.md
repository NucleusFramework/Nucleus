# in-app-purchase internals

`in-app-purchase` exposes `InAppPurchase` over `Windows.Services.Store` (Rust, forked from
tauri-plugin-iap, THIRD_PARTY_NOTICES.md §8). macOS and Linux get the Kotlin-only
`UnsupportedStoreBackend`, with no library.

## Protocol

One JSON protocol (`protocol.rs` ↔ `internal/Wire*.kt`): `nativeRequest(id, method, json)` is
answered exactly once through `onResponse`, `nativeCancel(id)` cancels the store operation, and
out-of-band changes arrive through `onEvent`. The Kotlin side owns validation, so the native side
only sees well-formed requests.

## Threading rules (found the hard way)

Each request runs on a worker thread of its own, which dictates two rules:

- **The MTA is pinned** with `CoIncrementMTAUsage`. Without it the last worker leaving tears the
  apartment down, and the next Store call on a new thread crashes the JVM with `0xC0000005` and
  **no hs_err** (reproduced by removing the pin).
- **Workers detach with a scoped `attach_current_thread` guard.** Exiting attached
  (`attach_current_thread_as_daemon`) leaves a dangling JavaThread; detaching from a TLS destructor
  (`attach_current_thread_permanently`) deadlocks (jstack hangs).

`StoreContext::GetDefault()` returns a new instance per call, so every purchase binds its own owner
window through `IInitializeWithWindow`.

- **The license listener is subscribed from a thread of our own, in the MTA.** `nativeInit` runs
  on whatever thread first collects an update flow — in practice the UI thread, an STA (tao's
  `OleInitialize`), where `RoInitialize(MULTITHREADED)` fails with `RPC_E_CHANGED_MODE`. Subscribed
  there, `OfflineLicensesChanged` never reached the app on a real Store install (trial converted
  to a full license, no update). `register_in_mta` makes the subscription on a short-lived MTA
  thread instead; the Rust test calls it from an STA and checks the apartment.

## Licenses and updates

`OfflineLicensesChanged` says *that* licenses changed, not what: each event carries the full list
of active add-on licenses, and `LicenseTracker` diffs it against the previous one. A purchase in
flight through `InAppPurchase.purchase` is absorbed so it is not reported twice. Consumables are
not licenses and are never tracked there.

## App license

`appLicense` reads `StoreAppLicense` (offline). A time-limited trial ends at `ExpirationDate`;
`TrialTimeRemaining` only counts down usage-limited trials and, on a 7-day Store trial
(observed), reports the unlimited `TimeSpan` sentinel. `trial_time_left` therefore derives the
time left from the expiration whenever there is one, and falls back to the span (sentinel →
`null`) only without one. Outside a trial there is no time left. Each `licensesChanged` event
carries the app license next to the add-on licenses; Kotlin reports it only when its state
changes (the trial countdown alone is not news), against a baseline read when the first
collector subscribes.

Observed on the real API: an install the Store did not license (here the unpackaged test process
with `diag.allowUnpackaged`) gets `IsActive = true`, `IsTrial = false` and an empty
`SkuStoreId`. `AppLicense.isFullLicense` therefore also requires a `skuStoreId`, and
`WindowsNativeTortureTest` pins that answer. A real Store install (Nucleus Lab, 7-day trial)
reads `SkuStoreId = 9MV8ZRKS4KT4/0011`, so the rule holds; on that trial `TrialUniqueId` is
empty and `IsTrialOwnedByThisUser` is `false` too — like `TrialTimeRemaining`, they only
describe usage-limited trials.

`purchaseApp` buys `GetStoreProductForCurrentAppAsync`'s product and shares `purchase_outcome`
with add-on purchases, so both map Store statuses the same way.

## App updates

`StoreUpdates` rides the same bridge (`updates.check`, `updates.install`,
`updates.openStorePage`). `GetAppAndOptionalStorePackageUpdatesAsync` gives the version
(`Package.Id.Version`) and the mandatory flag; the app and its optional packages may update
together, so Kotlin reports the newest version. Installing is an
`IAsyncOperationWithProgress`: `wait_with_progress` shares `wait`'s cancellation, and each
`StorePackageUpdateStatus` goes out as an `updateProgress` event carrying the request id, which
`NativeStoreBackend` routes to the flow of that request (`progressListeners`). Unlike licenses,
update checks need the Store service: an unlisted app gets `0x803F6101` (pinned by
`WindowsNativeTortureTest`, reported as `Platform`). Only a real listing can show an update.

## Failure modes

- No package identity: fails fast with `NotPackaged`, before touching WinRT.
- Package without a Partner Center listing: `NotAssociated` (`0x803F6107`).

## Testing a packaged build

`Invoke-CommandInDesktopPackage` silently does nothing when launching with arguments;
`IApplicationActivationManager::ActivateApplication` works. There is no simulator for
`Windows.Services.Store` (`CurrentAppSimulator` only serves the legacy
`Windows.ApplicationModel.Store` API): install the app once from its (hidden) Store listing,
then sideload the development build under the same Package Family Name so the Store's licenses
apply to it.
