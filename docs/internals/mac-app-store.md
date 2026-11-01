# mac-app-store internals

`mac-app-store` exposes `MacAppStore` over StoreKit 2. It is independent of `microsoft-store`:
no shared module, its own models, shaped by what StoreKit reports. Windows and Linux get the
Kotlin-only `UnsupportedStoreBackend`, with no library.

## Native layout

StoreKit 2 is Swift-only, so `nucleus_macstore` has two halves linked into one dylib:

- `NucleusMacStoreJni.m` — the JNI shim, in C like every other bridge
  (`nucleus_jni_clear_exception`, `native-common/nucleus_jni.h`);
- `MacStore.swift` — StoreKit, one `Task` per request.

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
  still get the Xcode environment and its history.
- `AppStore.sync()` raised the system Apple Account sign-in sheet once during these tests even
  in the Xcode environment; a sheet whose app quits stays on screen unresponsive
  (`AKAuthorizationRemoteViewService` / `storeuid`).

## E2E

`scripts/mac-app-store-e2e.sh` builds a jpackage shell once under `build/e2e`, rewrites its
`.cfg` to the test runtime classpath (`e2eClasspath` task), re-signs it ad hoc with
`get-task-allow`, registers it, and runs `MacAppStoreE2E` inside it against
`src/test/resources/e2e/Products.storekit`, then as a bare `java` (`NotBundled`).
