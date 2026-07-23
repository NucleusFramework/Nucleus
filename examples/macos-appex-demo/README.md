# macOS Network Extension (`.appex`) demo

Reproduces the scenario from [issue #394](https://github.com/NucleusFramework/Nucleus/issues/394):
shipping a macOS **Network Extension** (`.appex`) inside a Nucleus JVM app, embedded under
`Contents/PlugIns/`, **signed with its own entitlements** (distinct from the host app).

Nucleus embeds and signs the extension for you via the `appExtensions {}` DSL:

```kotlin
macOS {
    entitlementsFile.set(file("packaging/app.entitlements"))   // host-app entitlements
    appExtensions {
        extension("NetworkFilter") {
            appex(file("build/appex/NetworkFilter.appex"))               // prebuilt .appex
            entitlements(file("packaging/extension/NetworkExtension.entitlements")) // ITS OWN
            // provisioningProfile(file("packaging/NetworkFilter.provisionprofile"))
        }
    }
}
```

Under the hood the plugin copies the `.appex` into `Contents/PlugIns/`, embeds its provisioning
profile (as `Contents/embedded.provisionprofile` inside the extension), signs the extension with
its own entitlements, then seals the outer app **without `--deep`** — so the extension keeps its
distinct signature. It does the same on the DMG/PKG re-seal path.

> Nucleus does not build the `.appex` — that stays Xcode / Kotlin/Native territory. Here a small
> `build.sh` compiles a minimal `NEFilterDataProvider` into a universal `.appex`.

## Layout

```
packaging/
  app.entitlements                 host-app entitlements (App Group + networkextension)
  extension/
    FilterDataProvider.m           minimal NEFilterDataProvider (allows all traffic)
    Info.plist                     NSExtension declaration (principal class, point id)
    NetworkExtension.entitlements  the EXTENSION's own entitlements
    build.sh                       compiles the universal .appex
src/main/kotlin/.../Main.kt        Compose app; inspects its own Contents/PlugIns at runtime
```

## Run it

```bash
# Build the .app with the extension embedded & signed (ad-hoc, no certificate needed):
./gradlew :examples:macos-appex-demo:createDistributable

# Launch it — the window lists the embedded extension and shows that the .appex
# carries its own signature/entitlements, separate from the app:
open build/compose/binaries/main/app/NetworkExtensionDemo.app
```

Inspect manually:

```bash
APP=build/compose/binaries/main/app/NetworkExtensionDemo.app
codesign --verify --deep --strict --verbose=2 "$APP"
codesign -d --entitlements :- "$APP/Contents/PlugIns/NetworkFilter.appex"
```

## Real distribution (Developer ID / App Store)

1. Request the Network Extension capability for your App ID, create App IDs + provisioning
   profiles for both the app and the extension (they need the same App Group).
2. Enable `signing { sign.set(true); identity.set("Developer ID Application: You (TEAMID)") }`.
3. Add each extension's `provisioningProfile(...)` and the app's `provisioningProfile.set(...)`.

Build the GraalVM native variant (the `.appex` is embedded & ad-hoc signed there too):

```bash
GRAALVM_HOME=/path/to/graalvm ./gradlew :examples:macos-appex-demo:packageGraalvmNative
# → build/compose/tmp/main/graalvm/output/NetworkExtensionDemo.app/Contents/PlugIns/NetworkFilter.appex
```

### Caveats

- **GraalVM native images are always ad-hoc signed**, so the embedded extension is ad-hoc too.
  For a Developer-ID/notarized GraalVM DMG, configure `signing {}` (the GraalVM DMG re-seal goes
  through the same electron-builder path as the JVM one).
- Actually *installing/enabling* the extension uses the NetworkExtension management APIs
  (`NEFilterManager` / `NETunnelProviderManager`), called from the JVM via a native bridge —
  see https://nucleusframework.dev/en/docs/performance/native-code/. This example is about
  signing/bundling/shipping the `.appex`.
- Testing the extension at runtime without a paid account requires disabling SIP + AMFI on a
  dev VM / victim machine (`csrutil disable` + `nvram boot-args="amfi_get_out_of_my_way=0x1"`).
