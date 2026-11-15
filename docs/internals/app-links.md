# App links (verified `https://` links that open the app)

The desktop counterpart of Android App Links: a link on a host the app owns opens the app instead of
the browser, and arrives through the same `onDeepLink { }` as a custom scheme.

```kotlin
nativeDistributions {
    protocol("MyApp", "myapp")                    // custom scheme, every format
    appLinks("example.com", "*.example.com")      // https:// links, AppX / MSIX only today
}
```

| Platform | Status | Mechanism |
|---|---|---|
| Windows, AppX / MSIX | **shipped** | `windows.appUriHandler` |
| Windows, NSIS / MSI / portable / ZIP | impossible | needs package identity; the build warns |
| macOS | **not implemented**, plan below | Universal Links (Associated Domains) |
| Linux | impossible | no verified-link mechanism in freedesktop |

Never answer a "can I declare https:// as a deep link" question with `protocol("X", "https")`: that
registers the app as a **browser** (Windows Default Apps, `x-scheme-handler/https`, macOS
`CFBundleURLTypes`), not as the handler of one host.

## Windows

### Packaging

`AppXAppLinks` (plugin `internal/`) validates the hosts (bare DNS names, one optional leading `*.`,
lowercased, deduplicated) and writes the `<Extensions>` entry:

```xml
<uap3:Extension xmlns:uap3="…/uap/windows10/3" xmlns:uap5="…/uap/windows10/5" Category="windows.appUriHandler">
  <uap3:AppUriHandler>
    <uap3:Host Name="example.com" />
    <uap5:Host Name="*.example.com" />   <!-- wildcards need uap5 (1709) -->
  </uap3:AppUriHandler>
</uap3:Extension>
```

- The namespaces are declared **on the element**, not on `<Package>`, so the extension works with
  electron-builder's stock template, the widgets template and any user `customManifest` alike.
  makeappx accepts it, and accepts it with electron-builder's default `MinVersion` 10.0.14316.0
  even though `uap3:AppUriHandler` is documented as 1607 (14393).
- `AbstractElectronBuilderPackageTask.stageAppXManifest()` assembles one extensions file
  (`appx-extensions.xml`, or `widgets/widget-extensions.xml` with widgets): widgets XML, then app
  links, then the user's own `customExtensions`. electron-builder has a single
  `customExtensionsPath`, hence the merge.
- No `desktop2:Parameters` is needed: Windows passes the full URL as **argv[0]** of the full-trust
  exe (`AppLinksDemo.exe https://host/path?q=1`), which `DeepLinkHandler.parseUriFromArgs` already
  delivers, and the single-instance relay forwards to a running instance. **No runtime code.**

### What the host must serve

`https://<host>/.well-known/windows-app-web-link` (no extension), JSON, over HTTPS, no redirect:

```json
[{ "packageFamilyName": "Publisher.App_xxxxxxxxxxxxx", "paths": ["*"], "excludePaths": [], "allowSubdomains": true }]
```

`allowSubdomains` is needed for a `*.` host. Check the file before publishing it with
`%windir%\System32\AppHostRegistrationVerifier.exe <host> <PFN> <file>` (no output = valid).
Windows re-validates on its own schedule (Microsoft: same day to 8 days). The result lives per user
under `HKCU\Software\Classes\Local Settings\Software\Microsoft\Windows\CurrentVersion\AppModel\SystemAppData\<PFN>\AppUriHandlers\<host>`
(`AllowedPaths`, `LastSuccessfulValidationTime`, `FailedValiationCount`, sic).

### Behaviour measured on Windows 11 24H2 (2026-11-15)

- A **sideloaded / developer-registered** package's hosts resolve even when validation **failed**
  (`FailedValiationCount=1`, the test domain does not exist): `Launcher.FindAppUriHandlersAsync`
  returns the package and the link opens the app. A Store install depends on the hosted file.
- Triggers: `ShellExecuteEx` (`Start-Process https://…`, Win+R, a link in another app) and
  `Launcher.LaunchUriAsync`. Clicks inside Chrome / Chromium Edge stay in the browser.
- When the default browser is not the app, Windows shows **"Select an app to open this website"**
  (app vs default browser, "Always" / "Just once") the first time **per host**. It is a system
  flyout `OpenWith.exe -Embedding` hosts; UI Automation from the root did not reach it. After an
  answer it is not asked again for that host. Settings › Apps › Apps for websites lists each host
  with an on/off toggle.
- `*.example.com` matches `a.example.com` but **not** `example.com`; declare both if needed.
  `http://` links on a declared host also resolve to the app.
- `LaunchUriAsync` with `TargetApplicationPackageFamilyName` does **not** take the app URI handler
  route (it wants a `windows.protocol` for `https`) and falls back to Open With.

### E2E

Fixture: `examples/app-links-demo` (AppX + NSIS, hosts `applinks.nucleus.test` and
`*.applinks-wild.nucleus.test`; logs argv and every `onDeepLink` to `%USERPROFILE%\app-links-demo.log`,
outside AppData because an MSIX redirects AppData writes).

```bash
# Local quirk on this machine: electron-builder's own windows-kits bundle fails to spawn
export ELECTRON_BUILDER_WINDOWS_KITS_PATH='C:\Program Files (x86)\Windows Kits\10\bin\10.0.22621.0\x64'
./gradlew :examples:app-links-demo:packageAppX :examples:app-links-demo:packageNsis
```

1. AppX (unsigned, so register the unpacked layout in developer mode): unzip the `.appx`, then
   `Add-AppxPackage -Register <dir>\AppxManifest.xml`.
2. Resolution without UI: `[Windows.System.Launcher]::FindAppUriHandlersAsync([Uri]'https://applinks.nucleus.test/x')`
   must list the PFN; `https://other.nucleus.test/` and the bare wildcard domain must not.
3. Cold start: app closed, `Start-Process 'https://applinks.nucleus.test/cold/1?x=1'` (answer the
   picker the first time) → log shows `launch args=[https://…]` then `deeplink https://…`.
4. Warm start: app open, another link → a second pid logs `launch args`, the **first** pid logs the
   `deeplink` (single-instance relay).
5. NSIS: build log prints the `appLinks: Nsis has no package identity…` warning; after a silent
   install (`/S /currentuser`) `FindAppUriHandlersAsync` lists nothing, `HKCU\Software\Classes\https`
   is untouched, and `app-links-demo://` still works.

Results 2026-11-15: all five pass (JVM AppX + NSIS). GraalVM AppX goes through the same task and the
same argv path but was not run.

## macOS plan (not implemented)

Universal Links. Everything below is a plan for whoever picks it up; verify each assumption.

### What Apple requires

- Entitlement `com.apple.developer.associated-domains` = `["applinks:example.com", "applinks:*.example.com"]`.
  It is a **restricted** entitlement: the app must embed a provisioning profile that grants it
  (`Contents/embedded.provisionprofile`), for Developer ID builds as well as App Store ones. An
  ad-hoc or unprovisioned signature makes the app **fail to launch** (AMFI kills it), so never add
  the entitlement without a profile.
- The host serves `https://<host>/.well-known/apple-app-site-association` (JSON, no redirect),
  fetched through Apple's CDN, with `applinks.details[].appIDs = ["<TEAMID>.<bundleID>"]` and
  `components`. For local tests: `applinks:host?mode=developer` + `swcutil developer-mode -e true`
  bypasses the CDN.
- Delivery is **not** the `kAEGetURL` Apple Event the custom scheme uses: AppKit calls
  `-[NSApplicationDelegate application:continueUserActivity:restorationHandler:]` with an
  `NSUserActivity` of type `NSUserActivityTypeBrowsingWeb`, whose `webpageURL` is the link.
- In practice only Safari (and apps calling `NSWorkspace open` on the URL) honour them on macOS;
  Chrome opens the page.

### Plugin

1. Reuse `appLinkHosts` (same declaration as Windows; wildcards map 1:1).
2. When `appLinkHosts` is non-empty and the target is a signed macOS build, add the
   `associated-domains` entitlement to the entitlements file the plugin generates for every macOS
   path (JVM `.app`, GraalVM `.app`, DMG / PKG / App Store PKG), the same place the sandbox
   entitlements are merged.
3. New DSL `macOS { provisioningProfile.set(file(…)) }` (check first whether one already exists for
   the App Store PKG). Copy it to `Contents/embedded.provisionprofile` **before** signing. Without a
   profile: fail the build when `appLinks` is set (better than an app AMFI refuses), or skip the
   entitlement with a warning; decide with the user.
4. Unsigned / ad-hoc dev builds (`runDistributable`): never add the entitlement.

### Runtime

5. Tao owns `NSApp` (no AWT). Find where the Tao backend installs its `kAEGetURL` handler
   (`decorated-window-tao`, `TaoDeepLinkBridge.kt` on the Kotlin side, the Rust/ObjC side in the tao
   patches) and add `application:continueUserActivity:restorationHandler:` to the same app delegate:
   when `activityType == NSUserActivityTypeBrowsingWeb`, hand `webpageURL.absoluteString` to the same
   callback, which ends in `DeepLinkHandler.deliver(URI)`. Return `YES`.
   tao's `NSApplicationDelegate` may not forward unknown selectors: add the method through a tao
   patch (`vendor/tao-patches`), like the other delegate additions.
6. Cold start: the activity arrives after `applicationDidFinishLaunching`, so a URI received before
   `onDeepLink { }` registers must be stored and delivered once, exactly like the Apple Event path.

### E2E

7. Developer ID–signed build with a profile carrying the entitlement, `?mode=developer` host,
   `swcutil developer-mode -e true`, AASA served by a local HTTPS server trusted by the system, then
   `open https://host/path` (cold and warm) and a click in Safari. `swcutil dl -d <host>` shows
   what the system resolved.
