# location — work plan

> **DELETE THIS FILE BEFORE MERGING PR #726.** It tracks the remaining work across the
> Windows / macOS / Linux agents and must not land on `nucleus-3.0`.

Desktop first. Rules: JNI only (no FFM), JDK 17, no reflection, no heavy dependencies,
GraalVM native-image must work.

## 1. Prove what the PR claims

- [x] Windows x64: `location-demo` built as a GraalVM native image and run — prints a fix
- [ ] macOS: same, native image — prints a fix (hardened runtime + entitlement)
- [ ] Linux: same, native image — prints a fix
- [ ] Linux: XDG portal path on GNOME
- [ ] Linux: XDG portal path on KDE
- [ ] Linux: GeoClue fallback without a portal
- [ ] Linux: Flatpak stays on the portal
- [ ] Windows ARM64: DLL loads and returns a fix (JVM — no native-image toolchain there)
- [ ] Fix `reachability-metadata.json` if any of the above breaks

## 2. Lab probe

- [ ] `location` probe in the Lab (authorization, request, one-shot fix, live updates)
- [ ] Windows: probe works on the JVM
- [ ] Windows: probe works in the Lab native image (`-Dlab.probe=location`)
- [ ] macOS: probe works (JVM + native image, packaged Lab with the entitlement)
- [ ] Linux: probe works (JVM + native image)

## 3. Visible gaps

- [ ] Linux: parent the portal consent dialog to the app window (`x11:` / `wayland:` via
      xdg-foreign, the way `share` resolves it from the Tao window); today `Start` gets `""`
- [ ] Linux: verified on X11 and Wayland that the dialog sits above the app
- [ ] macOS: plugin DSL generating the Info.plist usage keys + the location entitlement,
      instead of raw XML and a hand-written `entitlements.plist`
- [ ] macOS: `location-demo` drops its `packaging/macos/entitlements.plist` and still prompts

## 4. Authorization API

- [ ] Observable authorization (`StateFlow<LocationAuthorization>`) fed by native signals
- [ ] Windows: turning location off in Settings updates the state while the app runs
- [ ] macOS: `locationManagerDidChangeAuthorization` updates the state while the app runs
- [ ] Linux: documented as best effort (no portal signal)
- [ ] `openLocationSettings()` — Windows `ms-settings:privacy-location`, macOS Privacy →
      Location Services, Linux best effort
- [ ] Windows / macOS / Linux: settings page opens
- [ ] `apiDump` committed, README / `docs/internals/location.md` updated

## 5. Android / iOS / web — borrow from maplibre-compose and compass

Reference code: [maplibre-compose `lib/location`](https://github.com/maplibre/maplibre-compose/tree/main/lib/location)
and [jordond/compass](https://github.com/jordond/compass) (`compass-geolocation-*`,
`compass-permissions-*`). Read their implementation per platform, keep what fits our API.

- [ ] Android: compare our `LocationManager` backend with maplibre's `AndroidLocationProvider` /
      `AndroidLocationPermissionRequester` and compass's `StandardLocationProvider` /
      `PermissionRequester` (provider selection, last-known fix, activity tracking)
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
- [ ] Every platform: open the system / app settings page (maplibre `SystemSettingsLauncher`)

## Final

- [ ] PR description updated
- [ ] This file deleted
