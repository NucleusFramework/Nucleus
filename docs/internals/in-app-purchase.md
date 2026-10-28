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

## Licenses and updates

`OfflineLicensesChanged` says *that* licenses changed, not what: each event carries the full list
of active add-on licenses, and `LicenseTracker` diffs it against the previous one. A purchase in
flight through `InAppPurchase.purchase` is absorbed so it is not reported twice. Consumables are
not licenses and are never tracked there.

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
