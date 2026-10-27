# location — work plan

> **DELETE THIS FILE BEFORE MERGING PR #726.** It tracks the remaining work across the
> Windows / macOS / Linux agents and must not land on `nucleus-3.0`.

Desktop first. Rules: JNI only (no FFM), JDK 17, no reflection, no heavy dependencies,
GraalVM native-image must work.

How to verify (any OS):

- Demo, JVM: `./gradlew :examples:location-demo:run`
- Demo, native image: `./gradlew :examples:location-demo:createGraalvmNativeDistributable`, then run
  `examples/location-demo/build/compose/binaries/main/graalvm-app/LocationDemo/location-demo`
- Lab, JVM: `LAB_PROBE="system.location?request=foreground&fix&cached&track" ./gradlew :examples:lab:app:run`
  (other params: `accuracy=approximate`, `settings`)
- Lab, native image: `./gradlew :examples:lab:app:createGraalvmNativeDistributable`, then run
  `examples/lab/app/build/compose/binaries/main/graalvm-app/NucleusLab/nucleus-lab` with the same
  `LAB_PROBE`
- Natives: `./gradlew :location:buildNative<Os>` (Rust crate in `location/src/main/native`)

## 1. Prove what the PR claims

- [x] Windows x64: `location-demo` built as a GraalVM native image and run — prints a fix
- [ ] macOS: same, native image — prints a fix (hardened runtime + entitlement)
- [x] Linux: same, native image — prints a fix (GNOME 50 Wayland, GraalVM CE 25)
- [x] Linux: XDG portal path on GNOME (xdg-desktop-portal 1.21.1). Found and fixed: the portal shares
      one GeoClue client across all sessions — see `docs/internals/location.md` (`Hub` in `linux.rs`)
- [x] Linux: XDG portal path on KDE (nested KWin + xdg-desktop-portal-kde 6): host app, and a
      Flatpak app through the KDE consent dialog (granted by hand), stored grant, stored refusal
- [x] Linux: GeoClue fallback without a portal (private session bus without portal, real GeoClue)
- [x] Linux: Flatpak stays on the portal (`flatpak build` sandbox: no bus → `Restricted`, never
      GeoClue; stored refusal → `Denied`)
- [ ] Windows ARM64: DLL loads and returns a fix (JVM — no native-image toolchain there).
      No ARM64 machine available; the DLL is built and shipped
- [x] `reachability-metadata.json` holds on Windows (demo + Lab native images) and Linux (demo); re-check on macOS

## 2. Lab probe

- [x] `system.location` probe in the Lab (authorization, request, one-shot fix, cached fix, live
      updates, open settings), drivable with `LAB_PROBE` params
- [x] Windows: probe works on the JVM
- [x] Windows: probe works in the Lab native image
- [ ] macOS: probe works (JVM + native image). The Lab's macOS packaging needs the location usage
      keys and the `com.apple.security.personal-information.location` entitlement first
      (see section 3), otherwise Core Location never prompts
- [x] Linux: probe works on the JVM (host, and Flatpak sandbox on Wayland and XWayland)
- [x] Linux: probe works in the Lab native image
- [x] Linux: location turned off (GNOME) → `isAvailable` false, request `Denied` (was `NotDetermined`)

## 3. Visible gaps

- [x] Linux: parent the portal consent dialog to the app window — `request_authorization` passes
      the parent to `Start`; JVM `Geolocation.requestAuthorization(LocationPortalParent(...))`;
      `NucleusWindow.requestLocationAuthorization()` in `nucleus-application` resolves it from the
      Tao window (`xdgPortalParent()`, Wayland export closed once answered). Compiles for the Linux
      target (`cargo check`), **not run on Linux yet**
- [x] Linux: verified on X11 (XWayland) and Wayland that the dialog sits above the app (Lab in a
      Flatpak sandbox: host apps never get a dialog). GNOME refuses the dialog to an app that is
      not focused (`Response` 2), so an unparented request from a windowless sandboxed app fails
- [ ] macOS (plugin, needs a Mac to verify): DSL generating the Info.plist usage keys
      (`NSLocationUsageDescription`, `NSLocationWhenInUseUsageDescription`, optionally
      `NSLocationAlwaysAndWhenInUseUsageDescription`) and adding
      `com.apple.security.personal-information.location` to the entitlements, in both the JVM and
      GraalVM pipelines. Starting points: `MacOSPlatformSettings.infoPlist` in
      `plugin-build/plugin/.../dsl/PlatformSettings.kt`, `default-entitlements.plist` (+ the two
      sandbox variants) unpacked by `AbstractUnpackDefaultApplicationResourcesTask`, used through
      `mac.entitlementsFile.orElse(defaultAppEntitlements)` in `configureJvmApplication.kt` and
      `configureGraalvmApplication.kt`. A user `entitlementsFile` must keep working
- [ ] macOS: `location-demo` drops its `packaging/macos/entitlements.plist` and still prompts

## 4. Authorization API

- [x] Observable authorization: `authorizationUpdates(pollInterval = 1.seconds): Flow` in common
      code (polls the platform status, emits changes once) — a cold `Flow`, not a `StateFlow`,
      so it needs no scope; `stateIn` it where a `StateFlow` is wanted
- [ ] Windows: turning location off in Settings updates the state while the app runs. Not done
      by the agent (it must not change system privacy settings): open the Lab probe, toggle
      *Settings → Privacy & security → Location*, watch "authorization" flip to `Denied`
- [ ] macOS: revoking access in System Settings updates the state while the app runs
- [x] Linux: documented as best effort (no portal signal)
- [x] `openSettings()` — Windows `ms-settings:privacy-location`, macOS Privacy → Location
      Services, GNOME `gnome-control-center location`, Android app details, iOS app settings,
      web `false`
- [x] Windows: settings page opens (Lab probe)
- [x] Linux (GNOME): settings page opens (`gnome-control-center location`; `false` inside Flatpak)
- [ ] macOS: settings page opens
- [x] `apiDump` committed (`location`, `nucleus-application`), README / `docs/internals/location.md`
      updated

## 5. Android / iOS / web — borrow from maplibre-compose and compass

Reference code: [maplibre-compose `lib/location`](https://github.com/maplibre/maplibre-compose/tree/main/lib/location)
and [jordond/compass](https://github.com/jordond/compass) (`compass-geolocation-*`,
`compass-permissions-*`). Read their implementation per platform, keep what fits our API.

- [ ] Android: compare our `LocationManager` backend with maplibre's `AndroidLocationProvider` /
      `AndroidLocationPermissionRequester` and compass's `StandardLocationProvider` /
      `PermissionRequester` (provider selection, last-known fix, activity tracking)
- [x] Android (emulator API 36, `examples/location-android-demo`): coarse-only prompt, precise /
      approximate choice, approximate → precise upgrade dialog (added), background via the settings
      page, "don't ask again" (third request → `Denied` in < 100 ms, no dialog), location off →
      `isAvailable` false + `PermanentlyUnavailable`. Fixed: precise requests ran the fused provider
      at BALANCED (no GNSS); `authorization()` read `Denied` while the dialog was up
- [x] Android API 24 / 29 / 30 emulators (and API 36 again): same scenarios plus a rotation while
      the dialog is up. Fixed: the pause → resume prompt detection hung (API 30, the dialog
      recreated the activity) or returned before the answer — replaced by
      `PermissionRequestActivity`; `checkSelfPermission` lagging the answer returned `Denied` (API
      29); `isAvailable` was `false` before the first grant below API 28
- [ ] Android: report "denied, do not ask again" (`shouldShowRequestPermissionRationale`,
      compass `DeniedForever`, maplibre `NotGranted(canRequest, shouldShowRationale)`)
- [ ] Android: report the granted accuracy (fine vs coarse)
- [ ] Android: optional Play services / HMS backend the way maplibre plugs it
      (`AndroidLocationBackend` via `ServiceLoader`) — only if it stays reflection-free
- [ ] iOS: compare with maplibre's `AppleLocationProvider` / `AppleLocationPermissionRequester`
      and compass's `LocationManagerDelegate` (main-thread rules, reduced accuracy,
      `requestTemporaryFullAccuracyAuthorization`)
- [ ] iOS: report `accuracyAuthorization` (full vs reduced)
- [ ] iOS / Android: heading provider (maplibre `HeadingProvider`)
- [ ] Web: compare with maplibre's `WebLocationProvider` and compass's `BrowserLocator`
      (Permissions API `change`, `maximumAge` / `timeout` mapping, error codes)
- [ ] Web: tests in real browsers (maplibre runs Chromium / Firefox / WebKit), not only Node
- [x] Every platform: open the system / app settings page (`openSettings()`; Android / iOS
      compiled only, not run on a device)

## Final

- [ ] PR description updated
- [ ] This file deleted
