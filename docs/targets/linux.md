# Linux Targets

Nucleus supports five Linux package formats.

## Formats

| Format | Extension | Auto-Update | Sandboxed |
|--------|-----------|-------------|-----------|
| DEB | `.deb` | Yes | No |
| RPM | `.rpm` | Yes | No |
| AppImage | `.AppImage` | Yes | No |
| Snap | `.snap` | No (Store) | No |
| Flatpak | `.flatpak` | No | Yes |

```kotlin
targetFormats(
    TargetFormat.Deb,
    TargetFormat.Rpm,
    TargetFormat.AppImage,
    TargetFormat.Snap,
    TargetFormat.Flatpak,
)
```

## General Linux Settings

```kotlin
nativeDistributions {
    linux {
        iconFile.set(project.file("icons/app.png"))

        // .desktop file settings
        shortcut = true
        packageName = "myapp"
        appRelease = "1"
        appCategory = "Utility"
        menuGroup = "Development"

        // StartupWMClass (helps window managers match the window)
        // Auto-derived from mainClass if null
        startupWMClass = "com-example-MyApp"
    }
}
```

## DEB Package

```kotlin
linux {
    // Maintainer (required for DEB)
    debMaintainer = "Your Name <dev@example.com>"

    // Dependencies injected into the .deb control file
    debDepends = listOf("libfuse2", "libgtk-3-0", "libasound2")

    // Version override for DEB
    debPackageVersion = "1.0.0"
}
```

## RPM Package

```kotlin
linux {
    // Dependencies injected into the RPM spec
    rpmRequires = listOf("gtk3", "libX11", "alsa-lib")

    // Version override for RPM (no dashes allowed)
    rpmPackageVersion = "1.0.0"

    // RPM license tag
    rpmLicenseType = "MIT"
}
```

## AppImage

[AppImage](https://appimage.org/) produces a single portable executable that runs on most Linux distributions without installation.

```kotlin
linux {
    appImage {
        // XDG category
        category = AppImageCategory.Utility

        // Desktop entry fields
        genericName = "My Application"
        synopsis = "A short description of the app"

        // Extra .desktop entries (key=value pairs)
        desktopEntries = mapOf(
            "Keywords" to "editor;text;",
        )
    }
}
```

### AppImage Categories

`AudioVideo`, `Development`, `Education`, `Game`, `Graphics`, `Network`, `Office`, `Science`, `Settings`, `System`, `Utility`

### Compression

AppImage startup time is heavily affected by the compression level. Using `CompressionLevel.Maximum` causes squashfs/FUSE decompression at every launch, resulting in startup times of **60 seconds or more**.

| Compression Level | Startup Impact | Recommended |
|---|---|---|
| `Store` | Fastest startup, largest file | Testing |
| `Normal` (default) | Good balance | **Production** |
| `Maximum` | 60s+ startup, ~20% smaller | Not recommended |

See [electron-builder#7483](https://github.com/electron-userland/electron-builder/issues/7483) for details.

### Build Requirements

AppImage requires `fuse` (or `libfuse2`) on the build host. On Ubuntu:

```bash
sudo apt-get install -y libfuse2
```

## Snap

[Snap](https://snapcraft.io/) packages for the Snap Store.

```kotlin
linux {
    snap {
        // Confinement level
        confinement = SnapConfinement.Strict // Strict, Classic, Devmode

        // Release grade
        grade = SnapGrade.Stable // Stable, Devel

        // Snap summary
        summary = "My awesome desktop app"

        // Base snap (Ubuntu core)
        base = "core22"

        // Plugs (permissions)
        plugs = listOf(
            SnapPlug.Desktop,
            SnapPlug.DesktopLegacy,
            SnapPlug.Home,
            SnapPlug.X11,
            SnapPlug.Wayland,
            SnapPlug.Network,
            SnapPlug.NetworkBind,
            SnapPlug.AudioPlayback,
        )

        // Auto-start on login
        autoStart = false

        // Compression
        compression = SnapCompression.Xz // Xz, Lzo
    }
}
```

!!! note
    The default `plugs` list includes: `Desktop`, `DesktopLegacy`, `Home`, `X11`, `Wayland`, `Unity7`, `BrowserSupport`, `Network`, `Gsettings`, `AudioPlayback`, `Opengl`.

### Snap Plugs

| Plug | `id` | Description |
|------|------|-------------|
| `SnapPlug.Desktop` | `desktop` | Desktop integration |
| `SnapPlug.DesktopLegacy` | `desktop-legacy` | Legacy desktop integration |
| `SnapPlug.Home` | `home` | Access to home directory |
| `SnapPlug.X11` | `x11` | X11 display access |
| `SnapPlug.Wayland` | `wayland` | Wayland display access |
| `SnapPlug.Unity7` | `unity7` | Unity7 desktop integration |
| `SnapPlug.BrowserSupport` | `browser-support` | Browser support |
| `SnapPlug.Network` | `network` | Network access |
| `SnapPlug.NetworkBind` | `network-bind` | Listen on network ports |
| `SnapPlug.Gsettings` | `gsettings` | GSettings access |
| `SnapPlug.AudioPlayback` | `audio-playback` | Audio playback |
| `SnapPlug.AudioRecord` | `audio-record` | Audio recording |
| `SnapPlug.Opengl` | `opengl` | OpenGL/GPU access |
| `SnapPlug.RemovableMedia` | `removable-media` | Removable media access |
| `SnapPlug.Cups` | `cups` | Printing |

### Build Requirements

Snap requires `snapd` and `snapcraft` on the build host:

```bash
sudo apt-get install -y snapd
sudo snap install snapcraft --classic
```

## Flatpak

[Flatpak](https://flatpak.org/) packages with sandboxed runtime. Flatpak targets use the [sandboxed build pipeline](../sandboxing.md#flatpak-sandbox) automatically.

```kotlin
linux {
    flatpak {
        // Freedesktop runtime
        runtime = "org.freedesktop.Platform"    // Default
        runtimeVersion = "23.08"                // Default
        sdk = "org.freedesktop.Sdk"             // Default

        // Application branch
        branch = "master"                       // Default

        // Sandbox permissions (defaults: --share=ipc, --socket=x11, --socket=wayland, --socket=pulseaudio, --device=dri)
        finishArgs = listOf(
            "--share=ipc",
            "--socket=x11",
            "--socket=wayland",
            "--socket=pulseaudio",
            "--device=dri",
            "--filesystem=home",
        )

        // License file
        license.set(project.file("LICENSE"))
    }
}
```

### Build Requirements

Flatpak requires `flatpak` and `flatpak-builder` with the target runtime and SDK installed. If these tools are missing, the packaging task will skip gracefully with a clear message.

**Using the `setup-nucleus` GitHub Action** (recommended):

```yaml
- uses: kdroidFilter/Nucleus/.github/actions/setup-nucleus@main
  with:
    flatpak: 'true'
```

**Manual setup:**

```bash
sudo apt-get install -y flatpak flatpak-builder
flatpak remote-add --if-not-exists flathub https://flathub.org/repo/flathub.flatpakrepo
flatpak install -y flathub org.freedesktop.Platform//23.08
flatpak install -y flathub org.freedesktop.Sdk//23.08
```

## Deep Links & File Associations on Linux

Protocol handlers and file associations declared at the top level are automatically injected into `.desktop` files as `MimeType` entries:

```kotlin
nativeDistributions {
    protocol("MyApp", "myapp")
    fileAssociation(mimeType = "application/x-myapp", extension = "myapp", description = "MyApp Doc")
}
```

This produces a `.desktop` file with:

```ini
MimeType=x-scheme-handler/myapp;application/x-myapp;
```

No manual `desktopEntries` override is needed for MimeType.

## Full Linux DSL Reference

### `linux { }`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `iconFile` | `RegularFileProperty` | — | `.png` icon file |
| `shortcut` | `Boolean` | `false` | Create `.desktop` file |
| `packageName` | `String?` | `null` | Override package name |
| `packageVersion` | `String?` | `null` | Override package version |
| `startupWMClass` | `String?` | `null` | `StartupWMClass` in `.desktop` |
| `appRelease` | `String?` | `null` | Application release number |
| `appCategory` | `String?` | `null` | Application category |
| `debMaintainer` | `String?` | `null` | DEB maintainer email |
| `menuGroup` | `String?` | `null` | Menu group |
| `rpmLicenseType` | `String?` | `null` | RPM license tag |
| `debPackageVersion` | `String?` | `null` | DEB version override |
| `rpmPackageVersion` | `String?` | `null` | RPM version override |
| `debDepends` | `List<String>` | `[]` | Extra DEB dependencies |
| `rpmRequires` | `List<String>` | `[]` | Extra RPM dependencies |

### `linux { appImage { } }`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `category` | `AppImageCategory?` | `null` | XDG application category |
| `genericName` | `String?` | `null` | Generic application name |
| `synopsis` | `String?` | `null` | Short description |
| `desktopEntries` | `Map<String, String>` | `{}` | Extra `.desktop` key-value pairs |

### `linux { snap { } }`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `confinement` | `SnapConfinement` | `Strict` | Confinement level |
| `grade` | `SnapGrade` | `Stable` | Release grade |
| `summary` | `String?` | `null` | Snap summary |
| `base` | `String?` | `null` | Base snap (e.g., `core22`) |
| `plugs` | `List<SnapPlug>` | 11 defaults | Snap plugs (permissions) |
| `autoStart` | `Boolean` | `false` | Auto-start on login |
| `compression` | `SnapCompression?` | `null` | Compression method |

### `linux { flatpak { } }`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `runtime` | `String` | `"org.freedesktop.Platform"` | Flatpak runtime |
| `runtimeVersion` | `String` | `"23.08"` | Runtime version |
| `sdk` | `String` | `"org.freedesktop.Sdk"` | SDK |
| `branch` | `String` | `"master"` | Application branch |
| `finishArgs` | `List<String>` | 5 defaults | Sandbox permissions |
| `license` | `RegularFileProperty` | — | License file |
