# App links (verified `https://` links that open the app)

The desktop counterpart of Android App Links: a link on a host the app owns opens the app instead of
the browser, and arrives through the same `onDeepLink { }` as a custom scheme.

```kotlin
nativeDistributions {
    protocol("MyApp", "myapp")                    // custom scheme, every format
    appLinks("example.com", "*.example.com")      // https:// links: AppX / MSIX, signed macOS builds
    macOS { provisioningProfile.set(file("packaging/MyApp.provisionprofile")) } // macOS needs it
}
```

| Platform | Status | Mechanism |
|---|---|---|
| Windows, AppX / MSIX | **shipped** | `windows.appUriHandler` |
| Windows, NSIS / MSI / portable / ZIP | impossible | needs package identity; the build warns |
| macOS, signed (Developer ID / App Store) + profile granting Associated Domains | **shipped** | Universal Links |
| macOS, unsigned / ad hoc, or no such profile | skipped | restricted entitlement; the build warns |
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

## macOS

### Packaging

Universal Links rest on `com.apple.developer.associated-domains`, a **restricted** entitlement: AMFI
refuses to launch an app claiming it without an embedded provisioning profile that grants it, for
Developer ID builds as well as App Store ones. So `MacAppLinks` (plugin `internal/`) only touches a
bundle when all of these hold, and otherwise leaves its entitlements alone:

- the build is signed with a real identity (`macOS { signing { sign = true } }`); unsigned and ad-hoc
  builds (`runDistributable`, dev) skip it at info level;
- `macOS { provisioningProfile }` is set and grants Associated Domains (read with
  `security cms -D -i`); otherwise the build warns and skips;
- the entitlements do not already declare associated domains (a user `entitlementsFile` with its own
  key, e.g. `applinks:host?mode=developer`, is used as is).

It then copies the profile to `Contents/embedded.provisionprofile`, and seals the bundle with the
entitlements plus `applinks:<host>` for each host (wildcards map 1:1) and, when missing,
`com.apple.application-identifier` / `com.apple.developer.team-identifier` taken from the profile
(swcd matches the AASA `appIDs` against them). Two places seal the bundle, both call it:

- `AbstractJPackageTask.modifyRuntimeOnMacOsIfNeeded()`, the JVM `.app` (`createDistributable`);
- `AbstractElectronBuilderPackageTask.resignApp()`, every signed DMG / ZIP / PKG, JVM and GraalVM
  (the GraalVM `.app` gets its profile there). The App Store PKG feeds the result to its own
  identifier injection.

`macOS { provisioningProfile }` used to reach the JVM bundle through jpackage's `--app-content`, which
keeps the file's own name: anything but `embedded.provisionprofile` landed as unsigned content and
failed jpackage's `codesign`. The jpackage task now copies it to `Contents/embedded.provisionprofile`.

### Runtime

Nothing to add. Safari hands the link to AppKit as an `NSUserActivity`
(`NSUserActivityTypeBrowsingWeb`); the vendored tao app delegate implements
`application:continueUserActivity:restorationHandler:` and turns `webpageURL` into the same
`Event::Opened` as the `kAEGetURL` path, so it reaches `NativeTaoBridge.dispatchDeepLink` →
`onDeepLink`, cold start included.

### What the host must serve

`https://<host>/.well-known/apple-app-site-association` (no extension, no redirect), fetched through
Apple's CDN (`https://app-site-association.cdn-apple.com/a/v1/<host>` shows what it holds).
`application/octet-stream` (GitHub Pages' type for it) is accepted.

```json
{ "applinks": { "details": [ { "appIDs": ["TEAMID.bundle.id"], "components": [ { "/": "/app/*" } ] } ] } }
```

Limit `components` to the paths the app handles: everything else keeps opening in the browser.

### Behaviour measured on macOS 27.2 (2026-11-16)

- Developer ID build, profile with Associated Domains, installed in `~/Applications` and launched once:
  swcd picked the domain up from the CDN within a minute, without developer mode.
- `open https://…` and `open -a Safari https://…` load the page in the browser: a URL typed or handed
  over is never diverted. Chrome ignores Universal Links.
- Safari then shows a banner "**<App>** — Open in the app <App>" above the page; its **Open** button
  delivers the link. Cold start: the app launches with no argv and gets the URL in `onDeepLink`.
  Warm start: the running instance gets it.

### E2E

Fixture: `examples/app-links-demo` (bundle ID `dev.nucleusframework.applinksdemo`, team `25Y9KCRMA3`).
`nucleusframework.dev` (GitHub Pages, repo `NucleusFramework/nucleus-website`,
`public/.well-known/apple-app-site-association`) serves an AASA for that app ID, scoped to
`/app-links-demo/*`.

```bash
./gradlew :examples:app-links-demo:createDistributable --no-configuration-cache \
  -PappLinksDemo.hosts=nucleusframework.dev \
  -PappLinksDemo.macIdentity="Your Name (TEAMID)" \
  -PappLinksDemo.macProfile=/path/to/applinksdemo.provisionprofile \
  -PappLinksDemo.macKeychain=$HOME/Library/Keychains/login.keychain-db  # when the cert is in two keychains
```

1. `codesign -d --entitlements - --xml <app> | plutil -p -` lists `applinks:nucleusframework.dev`
   and the identifiers; `Contents/embedded.provisionprofile` exists; the app launches (AMFI accepts).
2. `ditto` the app to `~/Applications`, `lsregister -f` it, launch it once.
3. Safari: open `https://nucleusframework.dev/app-links-demo/x/1?y=1` → banner → **Open**. The log
   (`~/app-links-demo.log`) shows `deeplink https://…` from the running pid (warm), or a new pid's
   `launch args=[]` followed by the `deeplink` (cold).

Results 2026-11-16: all three pass (JVM, Developer ID). Not run: GraalVM and App Store PKG (same
`resignApp()` path), a link click inside a Safari page (the banner is the same activity).
