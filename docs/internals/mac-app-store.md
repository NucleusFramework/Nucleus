# mac-app-store internals

`mac-app-store` exposes `MacAppStore` over StoreKit 2. It is independent of `microsoft-store`:
no shared module, its own models, shaped by what StoreKit reports. Windows and Linux get the
Kotlin-only `UnsupportedStoreBackend`, with no library.

## Native layout

StoreKit 2 is Swift-only, so `nucleus_macstore` links three parts into one dylib:

- `NucleusMacStoreJni.m` — the JNI shim, in C like every other bridge
  (`nucleus_jni_clear_exception`, `native-common/nucleus_jni.h`);
- `MacStore.swift`, `Purchases.swift`, `Updates.swift`, `DevSession.swift` — StoreKit, one
  `Task` per request;
- `NucleusStoreKitTest.m` — `SKTestSession` (StoreKit Testing), in Objective-C under ARC.

They meet at the C functions of `nucleus_macstore.h` (Swift exports with `@_cdecl`, imports the
shim's through `-import-objc-header`). `build.sh` compiles each half to an object per
architecture and links them with `swiftc`; the Swift runtime and concurrency are in the OS from
macOS 12, the minimum StoreKit 2 needs.

## Protocol

One JSON protocol (`MacStore.swift` ↔ `internal/Wire.kt`): `nativeRequest(id, method, json)` is
answered exactly once through `onResponse`, `nativeCancel(id)` cancels the Swift task, which
still answers (`cancelled`). The Swift registry is filled under a lock that the task's own
completion also takes, so a request that finishes at once still finds itself.

- Swift answers from its cooperative pool: the shim attaches for the call and detaches after,
  never leaving a thread attached.
- `NewStringUTF` takes modified UTF-8 and mangles characters outside the BMP (an emoji in a
  product name, pinned by the E2E): strings cross through UTF-16 (`NewString`).

## Not an app

StoreKit identifies the app by its main bundle. A bare `java` launch has one too — the JDK's
(`.jdk`, `CFBundlePackageType` `BNDL`, with a bundle id) — and `AppStore.canMakePayments` is true
there, so the first availability check reported `Available` (caught by the E2E). Only an
`APPL` bundle counts: anything else is `notBundled`, and store calls fail fast with it before
touching StoreKit.

## Purchases

`purchase` finishes a non-consumable or subscription at once and leaves a consumable
unfinished until `consume`, which finishes it by transaction id (absent from
`Transaction.unfinished`: already done, so a retry is a no-op). `list` is
`Transaction.currentEntitlements` (minus consumables) plus the verified, unrevoked consumables
of `Transaction.unfinished`. `entitlement` reads a subscription's group status (a status carried
by another tier of the group leaves this product not owned) and otherwise
`currentEntitlement(s)(for:)`, falling back to `Transaction.latest` to say revoked or expired.
Unverified transactions are never reported.

The sheet attaches with `purchase(confirmIn:)` (macOS 15.2+) to the window given — an `NSView *`
(`TaoWindow.nativeHandle`) or `NSWindow *` — else the key, main or first visible window; it
needs the app's main run loop.

### A purchase from the background

Found driving the Lab through accessibility while another app was in front: StoreKit's
`processPayment` for an app in the background left the `XcodeTest` client context for
`Sandbox` and asked for an Apple Account (`AccountManager … Sandbox authentication`, the
system's sign-in window from `storeuid`), while products and the app transaction stayed in
StoreKit Testing. The same bundle running the E2E bought normally, which ruled out the bundle
(a first hypothesis — other bundles registered under the same id, 14 of them on the test Mac —
was disproved: with the fix below the Lab buys under its real id). `purchase` now activates the
app and orders the sheet's window front first (`activate`). On macOS 14+ activation is
cooperative and the app may stay behind the active one, but the purchase stays in StoreKit
Testing and its sheet attaches to the Lab window.

### The sheet belongs to the customer (measured with `--sheet`)

- StoreKit ignores task cancellation: the sheet stays up and the request stays in flight.
- StoreKit nests its remote sheet (`NSWindow.ViewBridge.rendezvous`, 400×356) inside an empty
  one attached to the app window. Ending that chain from the app (`endSheet`, innermost first)
  hides it, but `purchase` then **never returns** and every later purchase hangs behind it. So
  the bridge does nothing on cancel; the customer's Cancel returns the call and the next
  purchase works.
- After Purchase, `storeuid` (a system process) shows "You're all set" (OK) and StoreKit
  returns only once it is closed. If the app quits first that window stays orphaned on screen,
  like the Apple Account sheet below.
- A purchase completed after its caller cancelled is finished and owned, but its answer goes to
  a request Kotlin dropped: the bridge sees `Task.isCancelled` and also emits it as a
  `transaction` event, so it reaches the app through `purchaseUpdates`.

### Consumables after a store-side event

After a renewal, a refund or a purchase made elsewhere, StoreKit stops listing a pending
consumable in `Transaction.unfinished` for the rest of the process, while it still holds it: the
next purchase of the product returns that same transaction, and a relaunch lists and replays it
(measured for each of the three events). `consume` looking it up in `Transaction.unfinished` then
found nothing, left it unfinished, and the next launch delivered it again — a double grant. The
bridge keeps the consumables it bought or delivered in this process (`PendingConsumables`) until
they are consumed: `list` adds them, `consume` finishes them from there. Pinned by the E2E
(`unconsumed consumables survive store-side events`).

## Updates

`nms_start_updates` (on the first `purchaseUpdates` collector, after the development session —
a listener started before it would watch the real store) runs, for the life of the process, a
`Transaction.updates` listener and, first, a walk of `Transaction.unfinished`:

- StoreKit replays unfinished transactions to `Transaction.updates` **only at process launch**,
  not to a listener started later in the same process (measured: a consumable bought before the
  first collector never came). The explicit walk makes the replay hold either way.
- A transaction is delivered once per process whichever path brings it first (an actor keeps the
  ids); a revoked one always goes through, since a refund keeps the transaction id. The E2E pins
  "exactly once" across a real relaunch (`crash-buy` / `crash-relaunch`).
- Delivery finishes everything but a live consumable, which waits for `consume`, as in
  `purchase`. Unverified transactions are never delivered nor finished.
- Transactions returned by `purchase()` are not on `Transaction.updates` (pinned by the E2E).
- Expiry is not a transaction: nothing arrives when a subscription runs out.

`appTransaction` reads `AppTransaction.shared` (macOS 13+; never `refresh()`, which prompts
for the Apple Account). `openStorePage` opens `macappstore://apps.apple.com/app/id<appId>`
through `NSWorkspace`, with the id given or the app transaction's (`nil` in StoreKit Testing, so
it fails instead of opening anything).

## App licence (`appLicense`)

The Mac App Store has no licence object and does not encrypt apps (FairPlay covers iOS only), so
`appLicense()` runs the checks Apple documents for an `AppTransaction`, on the device:

1. StoreKit's signature (`VerificationResult.verified`);
2. `bundleID` equals the running bundle's;
3. the device: `SHA384(nonce + deviceVerificationID)`, both lowercase UUID strings, equals
   `deviceVerification` (`AppStore.deviceVerificationID`, macOS 12+; the nonce and hash are on
   `VerificationResult<AppTransaction>`, macOS 13+), so a proof copied with an app container
   from another Mac fails;
4. no `revocationDate` (macOS 27 SDK only, see `revocationDate(_:)`).

A failed check is `invalid`; no proof at all (`AppTransaction.shared` throws: offline first
launch, no account) is `unavailable` and never thrown, because a paid app must choose a grace
period rather than lock out a customer who is offline. An unknown wire status maps to `Invalid`.

## StoreKit Testing without Xcode (dev mode)

`-Dnucleus.macAppStore.storeKitConfiguration` makes `NativeStoreBackend` send
`dev.configure` before the first request; the Swift side creates an `SKTestSession` in this
process. Verified on macOS 27 / Xcode 27.1:

- **StoreKitTest is `dlopen`ed**, never linked, from the active Xcode
  (`DEVELOPER_DIR`, else `xcode-select -p` when it has StoreKitTest, else
  `/Applications/Xcode.app`).
- **`bundleID` is set before `init`.** `-[SKTestSession bundleID]` returns a value already set;
  unset, it soft-links XCTest from `/System/Library/Frameworks/XCTest.framework`, which only
  exists under Xcode's test runner (`DYLD_FRAMEWORK_PATH`), and aborts the process. The bridge
  allocates the session, sets `bundleID` through KVC, then calls
  `initWithContentsOfURL:error:`.
- **storekitagent checks the caller.** It answers "<bundle id> is not installed for
  development" unless the app carries `com.apple.security.get-task-allow` (an ad-hoc signature
  is enough), and fails purchases with `StoreKitError.unknown` (agent:
  `kLSApplicationNotFoundErr`) unless LaunchServices knows the bundle.
- **Only the app itself can configure.** A helper process (even one carrying the app's bundle id
  in an embedded Info.plist) is identified by its own audit token and refused.
- The configuration persists in storekitagent per bundle id: later launches without a session
  still get the Xcode environment and its history, even a dev-signed build pointed at the
  Sandbox. Xcode removes it when it launches an app without one; the bridge does the same
  (`dev.forget`, before the first call when no `.storekit` is set): mach service
  `com.apple.storekit.configuration.xpc`, protocol `ASDOctaneServiceProtocol`,
  `removeConfigurationForBundleID:completion:`, declared locally so StoreKitTest is not loaded.
  storekitagent accepts it only from the bundle itself with get-task-allow (a bare binary gets
  `SKInternalErrorDomain` 4), so a build without it cannot clean up: a transaction in the Xcode
  environment without a `.storekit` then logs a warning naming the cause. Removing at the end of a
  session instead would lose what is meant to survive a restart (settings, unconsumed
  consumables).
- `AppStore.sync()` raised the system Apple Account sign-in sheet once during these tests even
  in the Xcode environment; a sheet whose app quits stays on screen unresponsive
  (`AKAuthorizationRemoteViewService` / `storeuid`).

## The App Store's side (`MacAppStore.testing`)

`dev.set` writes the session's properties through KVC (`disableDialogs`, `askToBuyEnabled`,
`billingRetryOnRenewalEnabled`, `billingGracePeriodEnabled`, `interruptedPurchasesEnabled`,
`timeRate`, `storefront`); `dev.act` calls its `…WithIdentifier:error:` methods on the product's
latest matching `SKTestTransaction`, or its `…WithProductIdentifier:error:` ones. Measured with
Xcode 27.1:

- `expireSubscriptionWithProductIdentifier:` moves the session's expiration date at once, but
  `subscription.status` and the entitlements keep the old one for 10–15 s.
- `declineAskToBuyTransactionWithIdentifier:` marks the session's transaction failed, yet
  StoreKit 2 keeps reporting it in `Transaction.unfinished` with a JWS identical to a real
  purchase (`inAppOwnershipType PURCHASED`), and the next purchase of the product returns that
  same transaction without a sheet. Nothing distinguishes it, so the API offers no decline.
- `buyProductWithIdentifier:` (deprecated in favour of a Swift-only API) still makes a purchase
  StoreKit 2 delivers through `Transaction.updates` as made outside the app.
- **StoreKitTest's `NSError **` cannot be trusted.** On a refusal (buying a subscription that is
  already active outside the app) the error it writes is over-released: the process crashed in
  `objc_autoreleasePoolPop` of the Swift task that made the call (every time in a GraalVM Lab,
  sporadically on the JVM). The calls first went through raw method implementations from Swift,
  which mishandled `alloc`/`init` and the out-parameter too; they now live in
  `NucleusStoreKitTest.m` under ARC, behind a protocol declaring the real signatures, and pass
  `NULL` for the error: a refusal is reported with our own message, and the crash is gone
  (`buyOutsideApp` refused twice in a row, pinned by the E2E).
- The settings persist in `storekitagent` per bundle id across launches (a run that turned the
  sheets off left them off for the next app under that id), hence `dev.settings` /
  `testing.settings()`, read through KVC on the same properties.

## Lab

`system.macAppStore` (`examples/lab/probes/system/…/macstore`) drives every call and the store
side through `MacAppStore.testing`, against `examples/lab/app/packaging/macos/NucleusLab.storekit`
(`./gradlew :examples:lab:app:runStoreKit`). Its switches are read from the session at start.
Driving it through accessibility: the Tao window exposes the StoreKit sheet as an `AXSheet`
child, with Cancel but no Purchase button.

## `runStoreKit` (plugin)

`AbstractRunStoreKitTask` (macOS only, registered next to `run`) makes the development bundle
once under `build/nucleus/storekit-app`: a jpackage app image of an empty jar with the app's
bundle id (`resolvedAppIdProvider`) and `--runtime-image <javaHome>` (the whole JDK, so any
module the classpath needs is there). `shell.properties` records JDK, bundle id and launcher
name; any change rebuilds the shell. Each run rewrites `Contents/app/<name>.cfg` — `run`'s
classpath (runtime jars, or ProGuard's output) and JVM arguments (`devJvmArgs`, shared with
`run`), `app.args` as `[ArgOptions]`, plus the configuration property — then signs ad hoc with
`get-task-allow` (`--deep` only when the shell is new: later runs only reseal the rewritten
`.cfg`), `lsregister -f`, and runs the launcher. Measured on the smoke app: first run ~15 s,
later runs under a second, code changes picked up without a rebuild of the shell, the
configuration cache stored and reused. Not covered: the vtool-patched JDK `run` uses for Liquid
Glass (the shell launcher is jpackage's).

## Sandbox and GraalVM (tested on one Mac, no Apple account)

- **App Sandbox, JVM.** The E2E runs entirely inside the sandbox (its code and `.storekit` in the
  bundle, ad-hoc signed with `app-sandbox`, `network.client`, `get-task-allow`). So does the Lab
  built by the plugin's sandboxed pipeline (`createSandboxedDistributable`, the Mac App Store
  channel), signed with its own sandbox entitlements plus `get-task-allow`. One trap, not
  specific to this module: a native library **extracted at run time** inside the sandbox
  container is quarantined, and Gatekeeper blocks `dlopen` behind a prompt ("Apple could not
  confirm…") — the process hangs. Packaged apps never extract (`nucleus.native.libraryPath`,
  `java.library.path` on `Contents/Frameworks`), so only an ad-hoc test bundle hits it.
- **GraalVM native image, no sandbox.** The Lab's native image runs the whole probe: the JNI
  responses and events (`reachability-metadata.json`) and the loose `libnucleus_macstore.dylib`
  next to the executable work as on the JVM.
- **GraalVM + App Sandbox is impossible**, which is why the plugin refuses the combination: the
  native image dies at start-up with `CSunMiscSignal.open() failed`, the sandbox denying
  `ipc-posix-sem-create /cSunMiscSignal-<pid>` (Substrate's signal handling uses a named POSIX
  semaphore; a sandboxed app may only create ones prefixed by its App Group).
- Not testable without an Apple account: the real App Store sandbox and production (server-side
  products, account sign-in), a build signed with a real certificate and provisioning profile.

## E2E

The E2E clears the session's history and turns its sheets off (`dev.clearTransactions`,
`dev.setDialogs`, internal protocol methods) so purchases run unattended, and plays the store's
side through the public `MacAppStore.testing` (refund, renewal, outside purchase, Ask to Buy,
billing retry with and without grace, auto-renew, expiry, storefront); an AWT `Frame` gives
StoreKit a window. `scripts/mac-app-store-e2e.sh` builds a jpackage shell once under `build/e2e`, rewrites its
`.cfg` to the test runtime classpath (`e2eClasspath` task), re-signs it ad hoc with
`get-task-allow`, registers it, and runs `MacAppStoreE2E` inside it against
`src/test/resources/e2e/Products.storekit`, then as a bare `java` (`NotBundled`).
