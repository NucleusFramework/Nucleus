# Nucleus Location

Kotlin Multiplatform geolocation, modelled on
[robius-location](https://github.com/project-robius/robius/tree/main/crates/location).

| Target | Backend |
|--------|---------|
| JVM — Windows | WinRT `Windows.Devices.Geolocation.Geolocator` (Rust JNI bridge) |
| JVM — macOS | Core Location (Rust JNI bridge, `objc2`) |
| JVM — Linux | XDG Location portal, falling back to GeoClue 2 over D-Bus (Rust JNI bridge, `zbus`) |
| Android | Framework `LocationManager` — fused provider on API 31+, GPS / network below. No Google Play services |
| iOS (`iosArm64`, `iosSimulatorArm64`) | Core Location |
| Web (`js`, `wasmJs`) | W3C Geolocation API (`navigator.geolocation`, Permissions API) |

Rust is used on desktop only; Android, iOS and the web call the platform directly from Kotlin.

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.location:<version>")
}
```

## Usage

```kotlin
// One fix; a fix already acquired in the last 5 minutes comes back without waiting.
val here = Geolocation.currentLocation(LocationAccuracy.Approximate, maxAge = 5.minutes)

// Fixes as the device moves, until the collecting coroutine is cancelled.
Geolocation.locationUpdates(minInterval = 2.seconds, minDistanceMeters = 10.0)
    .collect { location -> show(location.latitude, location.longitude) }
```

Both ask for authorization when it has not been determined yet, and throw
`LocationException(LocationError.AuthorizationDenied)` when it is refused. To choose when the
prompt appears — or to ask for background access — call it yourself first:

```kotlin
when (Geolocation.requestAuthorization(LocationAccess.Foreground, LocationAccuracy.Precise)) {
    LocationAuthorization.Foreground, LocationAuthorization.Background -> startTracking()
    else -> explainWhyLocationHelps()
}
```

`Geolocation` implements `LocationProvider`; depend on the interface to use a fake in tests.

### Semantics

- `currentLocation(maxAge = Duration.ZERO)` (the default) always measures a new fix. A positive
  `maxAge` lets the platform answer from its cache (`Location.isCached`), which is instant.
- `timeout` bounds the whole request and fails with `LocationError.Timeout`.
- `locationUpdates` is cold: every collector opens its own platform session, which stops when the
  collection ends. Transient outages (`TemporarilyUnavailable`, `Network`) are ridden out; the
  flow fails when access is denied or location becomes unavailable.
- `minInterval` and `minDistanceMeters` are hints the platform may not honour exactly.
- Optional fields of `Location` are `null` when the platform did not measure them.

## Platform setup

### Android

Declare the permissions you use in the app manifest — the library declares none, since which ones
an app holds is its own decision:

```xml
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<!-- LocationAccuracy.Precise -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<!-- LocationAccess.Background -->
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

The prompt needs a resumed activity; the library tracks it through a `ContentProvider` merged into
the app manifest (no `androidx.startup` dependency). Removing that provider from the merged
manifest disables the library.

### iOS and macOS

Core Location only prompts an app whose `Info.plist` explains why it wants the location:
`NSLocationWhenInUseUsageDescription` (and `NSLocationAlwaysAndWhenInUseUsageDescription` for
background access; macOS also reads `NSLocationUsageDescription`). With the Nucleus Gradle plugin:

```kotlin
nucleus.application {
    nativeDistributions {
        macOS {
            infoPlist {
                extraKeysRawXml = """
                    <key>NSLocationUsageDescription</key>
                    <string>Shows the weather where you are.</string>
                    <key>NSLocationWhenInUseUsageDescription</key>
                    <string>Shows the weather where you are.</string>
                """.trimIndent()
            }
        }
    }
}
```

An unpackaged run (`./gradlew run`, the IDE) has no such `Info.plist`, so it is never authorized on
macOS: test location with `runDistributable` or a packaged build. A sandboxed (App Store) build also
needs the `com.apple.security.personal-information.location` entitlement.

### Windows

Desktop apps need no manifest capability. Access follows *Settings → Privacy & security →
Location → Let desktop apps access your location*; Windows 11 24H2 and later prompt on the first
request. `authorization()` reports `NotDetermined` until a request in the current process has been
answered, since WinRT cannot tell "allowed" from "never asked" without asking.

### Linux

The XDG Location portal is used whenever it is present, sandboxed or not: it shows the desktop's
consent dialog and needs nothing from the app. Without a portal (or one without the Location
interface), an unsandboxed app talks to GeoClue 2 directly, which attributes the request to the
app's `.desktop` id — the installed desktop entry, else `NucleusApp.appId`. A portal refusal is
final: GeoClue is never tried behind the user's back. As on Windows, `authorization()` reports
`NotDetermined` until a request in this process succeeded.

### Web

Browsers only expose geolocation in a secure context (HTTPS or `localhost`), and only prompt when
a position is requested — `requestAuthorization` therefore asks for one. `authorization()` reads
the Permissions API state, queried when the provider is first used and kept current by its
`change` event, so the very first call may still report `NotDetermined`. `watchPosition` has no
interval or distance options: `locationUpdates` ignores `minInterval` / `minDistanceMeters` there,
and the browser reports when the position changes. `LocationAccess.Background` means nothing to a
page.

## Native library

`libnucleus_location` is built from `src/main/native` (a Rust crate; `cargo` + `rustup` required)
by `./gradlew :location:buildNativeWindows` / `buildNativeMacOs` / `buildNativeLinux` on the
matching host, and ships inside the JVM artifact under `nucleus/native/<platform>-<arch>/`. GraalVM
native-image metadata is included.
