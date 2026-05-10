# Migration from org.jetbrains.compose

Nucleus is a drop-in extension of the official JetBrains Compose Desktop plugin. All existing configuration is preserved — Nucleus only adds new capabilities.

## Step 1: Add the Plugin

```diff
 plugins {
     id("org.jetbrains.kotlin.jvm") version "2.3.10"
     id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
     id("org.jetbrains.compose") version "1.10.1"
+    id("io.github.kdroidfilter.nucleus") version "1.0.0"
 }
```

> The official `org.jetbrains.compose` plugin remains — Nucleus extends it, not replaces it.

## Step 2: Update Imports

Replace the JetBrains Compose DSL imports with the Nucleus equivalents:

```diff
-import org.jetbrains.compose.desktop.application.dsl.TargetFormat
+import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
```

This applies to all DSL types used in your `build.gradle.kts` (e.g. `TargetFormat`, `CompressionLevel`, `SigningAlgorithm`, etc.).

## Step 3: Use the Nucleus DSL

Replace the `compose.desktop.application` block with `nucleus.application` for packaging and distribution:

```diff
-compose.desktop.application {
+nucleus.application {
     mainClass = "com.example.MainKt"

     nativeDistributions {
         targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
         packageName = "MyApp"
         packageVersion = "1.0.0"

         macOS {
             bundleID = "com.example.myapp"
             iconFile.set(project.file("icons/app.icns"))
         }

         windows {
             iconFile.set(project.file("icons/app.ico"))
         }

         linux {
             iconFile.set(project.file("icons/app.png"))
         }
     }
 }
```

!!! tip "Using Compose Hot Reload?"
    Nucleus automatically propagates `nucleus.application.mainClass` to `compose.desktop.application.mainClass`, so [Compose Hot Reload](https://kotlinlang.org/docs/multiplatform/compose-hot-reload.html) tasks (`hotRun`, `hotSnapshotMain`) resolve the entry point with no extra configuration. You can still override it from the command line if needed:

    ```bash
    ./gradlew hotRun -PmainClass=com.example.MainKt
    ```

    On macOS with the `decorated-window-tao` backend, the plugin also auto-injects `-XstartOnFirstThread` into Hot Reload's `JavaExec` tasks so the Tao event loop has the main thread it requires.

## Step 4: Add Nucleus Features (Optional)

Enable the features you need. All are opt-in:

```kotlin
nucleus.application {
    mainClass = "com.example.MainKt"

    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb)
        packageName = "MyApp"
        packageVersion = "1.0.0"

        // --- New Nucleus features ---
        cleanupNativeLibs = true
        enableAotCache = true
        splashImage = "splash.png"
        compressionLevel = CompressionLevel.Maximum
        artifactName = "${name}-${version}-${os}-${arch}.${ext}"

        // Deep links
        protocol("MyApp", "myapp")

        // File associations
        fileAssociation(
            mimeType = "application/x-myapp",
            extension = "myapp",
            description = "MyApp Document",
        )

        // New Linux targets
        targetFormats(
            TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb,
            TargetFormat.AppImage, TargetFormat.Snap, TargetFormat.Flatpak, // NEW
        )

        // Publishing
        publish {
            github {
                enabled = true
                owner = "myorg"
                repo = "myapp"
            }
        }

        // NSIS customization
        windows {
            nsis {
                oneClick = false
                allowToChangeInstallationDirectory = true
                createDesktopShortcut = true
            }
        }
    }
}
```

## Step 5: Add Runtime Libraries (Optional)

```kotlin
dependencies {
    implementation(compose.desktop.currentOs)

    // Executable type detection
    implementation("io.github.kdroidfilter:nucleus.core-runtime:1.0.0")

    // AOT cache runtime (if using enableAotCache)
    implementation("io.github.kdroidfilter:nucleus.aot-runtime:1.0.0")

    // Auto-update (if using publish)
    implementation("io.github.kdroidfilter:nucleus.updater-runtime:1.0.0")

    // Native taskbar/dock progress bar
    implementation("io.github.kdroidfilter:nucleus.taskbar-progress:1.0.0")
}
```

## What Changes

| Feature | Before (compose) | After (nucleus)                                                                                                                       |
|---------|-------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| DSL entry point | `compose.desktop.application` | `nucleus.application`                                                                                                                 |
| DSL imports | `org.jetbrains.compose.desktop.application.dsl.*` | `io.github.kdroidfilter.nucleus.desktop.application.dsl.*`                                                                            |
| Target formats | DMG, PKG, MSI, EXE, DEB, RPM | + NSIS, AppX, Portable, AppImage, Snap, Flatpak, archives                                                                             |
| Native lib cleanup | Manual | `cleanupNativeLibs = true`                                                                                                            |
| AOT cache | Not available | `enableAotCache = true`                                                                                                               |
| Splash screen | Manual | `splashImage = "splash.png"`                                                                                                          |
| Deep links | Manual (macOS only via Info.plist) | Cross-platform `protocol("name", "scheme")`                                                                                           |
| File associations | Limited | Cross-platform `fileAssociation()`                                                                                                    |
| NSIS config | Not available | Full `nsis { }` DSL                                                                                                                   |
| AppX config | Not available | Full `appx { }` DSL                                                                                                                   |
| Snap config | Not available | Full `snap { }` DSL                                                                                                                   |
| Flatpak config | Not available | Full `flatpak { }` DSL                                                                                                                |
| Store pipeline | Not available | Automatic dual pipeline for store formats (PKG, AppX, Flatpak) with sandboxing for PKG and Flatpak                                    |
| Auto-update | Not available | Built-in with YML metadata                                                                                                            |
| Code signing | macOS only | + Windows PFX / Azure Artifact Signing                                                                                                |
| DMG appearance | Not customizable (jpackage defaults) | Full `dmg { }` DSL: background, icon size, window layout, content positioning, format ([details](targets/macos.md#dmg-customization)) |
| Artifact naming | Fixed | Template with `artifactName`                                                                                                          |

## Important Differences from Compose Desktop

### `homepage` is Required for Linux DEB

Unlike Compose Desktop (which uses jpackage), Nucleus uses electron-builder for packaging. Electron-builder **requires** the `homepage` property when building DEB packages. Without it, the build will fail with:

```
Please specify project homepage, see https://electron.build/configuration
```

Make sure to set it in your `nativeDistributions` block:

```kotlin
nativeDistributions {
    homepage = "https://myapp.example.com"
}
```

This also applies to GraalVM native image packaging (`packageGraalvmDeb`).

## What Stays the Same

Everything from the official plugin works unchanged:

- `mainClass`, `jvmArgs`
- `nativeDistributions` block (metadata, icons, resources)
- `buildTypes` / ProGuard configuration
- `modules()` / `includeAllModules`
- All existing Gradle tasks (`run`, `packageDmg`, `packageDeb`, etc.)
- `compose.desktop.currentOs` dependency
- Source set configuration
- [Compose Hot Reload](https://kotlinlang.org/docs/multiplatform/compose-hot-reload.html) — works out of the box since Nucleus extends the Compose plugin. `mainClass` set on `nucleus.application` is propagated automatically; `decorated-window-tao` projects also get `-XstartOnFirstThread` injected on macOS
