# Executable Type Detection

Detect at runtime which installer format was used to package your application.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.core-runtime:<version>")
}
```

```kotlin
import dev.nucleusframework.nucleus.core.runtime.ExecutableRuntime
import dev.nucleusframework.nucleus.core.runtime.ExecutableType
```

## Usage

```kotlin
// Get the executable type
val type: ExecutableType = ExecutableRuntime.type()

// Convenience checks
if (ExecutableRuntime.isDev()) {
    // Running via ./gradlew run (not packaged)
}

if (ExecutableRuntime.isDmg()) {
    // Installed from DMG
}

if (ExecutableRuntime.isNsis()) {
    // Installed from NSIS installer
}

if (ExecutableRuntime.isDeb()) {
    // Installed from DEB package
}
```

## Available Types

| Enum Value | Convenience Method | Platform |
|------------|-------------------|----------|
| `ExecutableType.DEV` | `isDev()` | All (not packaged) |
| `ExecutableType.DMG` | `isDmg()` | macOS |
| `ExecutableType.PKG` | `isPkg()` | macOS |
| `ExecutableType.EXE` | `isExe()` | Windows |
| `ExecutableType.MSI` | `isMsi()` | Windows |
| `ExecutableType.NSIS` | `isNsis()` | Windows |
| `ExecutableType.NSIS_WEB` | `isNsisWeb()` | Windows |
| `ExecutableType.PORTABLE` | `isPortable()` | Windows |
| `ExecutableType.APPX` | `isAppX()` | Windows |
| `ExecutableType.DEB` | `isDeb()` | Linux |
| `ExecutableType.RPM` | `isRpm()` | Linux |
| `ExecutableType.SNAP` | `isSnap()` | Linux |
| `ExecutableType.FLATPAK` | `isFlatpak()` | Linux |
| `ExecutableType.APPIMAGE` | `isAppImage()` | Linux |
| `ExecutableType.ZIP` | `isZip()` | All |
| `ExecutableType.TAR` | `isTar()` | All |
| `ExecutableType.SEVEN_Z` | `isSevenZ()` | All |

## How It Works

The plugin injects a `nucleus.executable.type` system property into the launcher `.cfg` file at packaging time. `ExecutableRuntime.type()` reads this property. When running via `./gradlew run`, the value is `dev`.

You can also read a custom property name:

```kotlin
val type = ExecutableRuntime.type("my.custom.property")
```

## Use Cases

```kotlin
// Show different update UI based on installer
when (ExecutableRuntime.type()) {
    ExecutableType.SNAP -> showSnapStoreUpdate()
    ExecutableType.FLATPAK -> showFlatpakUpdate()
    ExecutableType.APPX -> showMicrosoftStoreUpdate()
    else -> showNucleusAutoUpdate()
}
```
