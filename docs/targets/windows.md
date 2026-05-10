# Windows Targets

Nucleus supports five Windows installer formats and a portable mode.

## Formats

| Format | Extension | Auto-Update | Sandboxed |
|--------|-----------|-------------|-----------|
| NSIS | `.exe` | Yes | No |
| NSIS Web | `.exe` | Yes | No |
| MSI | `.msi` | Yes | No |
| AppX | `.appx` | No (Store) | No |
| Portable | `.exe` | No | No |

```kotlin
targetFormats(
    TargetFormat.Nsis,
    TargetFormat.Msi,
    TargetFormat.AppX,
    TargetFormat.Portable,
)
```

## General Windows Settings

```kotlin
nativeDistributions {
    windows {
        iconFile.set(project.file("icons/app.ico"))

        // Upgrade UUID — used by MSI for updates
        // Auto-generated if null, but should be fixed for production
        upgradeUuid = "d24e3b8d-3e9b-4cc7-a5d8-5e2d1f0c9f1b"

        // Console mode (shows terminal window)
        console = false

        // Per-user install (no admin required)
        perUserInstall = true

        // Start menu group
        menuGroup = "My Company"

        // Installation directory name
        dirChooser = true
    }
}
```

## NSIS Installer

NSIS produces a traditional Windows installer (`.exe`) with full customization.

```kotlin
windows {
    nsis {
        // Installer behavior
        oneClick = false                          // One-click install (no UI)
        allowElevation = true                     // Request admin rights
        perMachine = true                         // Install for all users
        allowToChangeInstallationDirectory = true // Let user pick directory

        // Shortcuts
        createDesktopShortcut = true
        createStartMenuShortcut = true

        // Post-install
        runAfterFinish = true
        deleteAppDataOnUninstall = false

        // Multi-language
        multiLanguageInstaller = true
        installerLanguages = listOf("en_US", "fr_FR", "de_DE", "es_ES", "ja_JP", "zh_CN")

        // Custom icons
        installerIcon.set(project.file("packaging/installer.ico"))
        uninstallerIcon.set(project.file("packaging/uninstaller.ico"))

        // Custom NSIS header/sidebar images
        installerHeader.set(project.file("packaging/header.bmp"))
        installerSidebar.set(project.file("packaging/sidebar.bmp"))

        // Custom NSIS script
        includeScript.set(project.file("packaging/custom.nsi"))
        // or full custom script:
        // script.set(project.file("packaging/installer.nsi"))

        // License agreement
        license.set(project.file("LICENSE"))
    }
}
```

### NSIS DSL Reference

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `oneClick` | `Boolean` | `true` | Silent one-click install |
| `allowElevation` | `Boolean` | `false` | Request admin privileges |
| `perMachine` | `Boolean` | `false` | Install for all users |
| `allowToChangeInstallationDirectory` | `Boolean` | `false` | Show directory chooser |
| `createDesktopShortcut` | `Boolean` | `true` | Create desktop shortcut |
| `createStartMenuShortcut` | `Boolean` | `true` | Create Start Menu shortcut |
| `runAfterFinish` | `Boolean` | `true` | Launch app after install |
| `deleteAppDataOnUninstall` | `Boolean` | `false` | Remove app data on uninstall |
| `multiLanguageInstaller` | `Boolean` | `false` | Multi-language installer UI |
| `installerLanguages` | `List<String>` | `[]` | NSIS language identifiers |
| `installerIcon` | `RegularFileProperty` | — | Installer `.ico` |
| `uninstallerIcon` | `RegularFileProperty` | — | Uninstaller `.ico` |
| `installerHeader` | `RegularFileProperty` | — | Header bitmap |
| `installerSidebar` | `RegularFileProperty` | — | Sidebar bitmap |
| `license` | `RegularFileProperty` | — | License file shown during install |
| `includeScript` | `RegularFileProperty` | — | Extra NSIS script to include |
| `script` | `RegularFileProperty` | — | Full custom NSIS script |

### Custom Default Installation Directory

By default, the NSIS installer installs to:

- **Per-user** (`perMachine = false`): `%LOCALAPPDATA%\Programs\{AppName}`
- **Per-machine** (`perMachine = true`): `%PROGRAMFILES%\{AppName}`

To let users choose the installation directory, set `allowToChangeInstallationDirectory = true` (requires `oneClick = false`):

```kotlin
nsis {
    oneClick = false
    allowElevation = true
    allowToChangeInstallationDirectory = true
}
```

To also override the **default path** shown in the directory chooser, create a custom NSIS include script using the `preInit` macro.

**1. Create** `packaging/nsis/installer.nsh`:

```nsis
!macro preInit
  SetRegView 64
  WriteRegExpandStr HKLM "${INSTALL_REGISTRY_KEY}" InstallLocation "C:\MyCompany\MyApp"
  WriteRegExpandStr HKCU "${INSTALL_REGISTRY_KEY}" InstallLocation "C:\MyCompany\MyApp"
  SetRegView 32
  WriteRegExpandStr HKLM "${INSTALL_REGISTRY_KEY}" InstallLocation "C:\MyCompany\MyApp"
  WriteRegExpandStr HKCU "${INSTALL_REGISTRY_KEY}" InstallLocation "C:\MyCompany\MyApp"
!macroend
```

**2. Reference it** in the DSL:

```kotlin
nsis {
    oneClick = false
    allowElevation = true
    allowToChangeInstallationDirectory = true
    includeScript.set(project.file("packaging/nsis/installer.nsh"))
}
```

!!! warning "Registry hive matters"
    - If `perMachine = true`, the installer reads from `HKLM`. Write to **both** `HKLM` and `HKCU` for safety.
    - If `perMachine = false`, only `HKCU` is checked. Writing to `HKLM` alone will have no effect.
    - Always write to both 32-bit and 64-bit registry views (`SetRegView 64` / `SetRegView 32`).

!!! info "References"
    - [electron-builder NSIS configuration](https://www.electron.build/nsis.html)
    - [Change default installation directory value (electron-builder#2855)](https://github.com/electron-userland/electron-builder/issues/2855)
    - [Change $INSTDIR to a custom path (electron-builder#1961)](https://github.com/electron-userland/electron-builder/issues/1961)

## AppX (Windows Store / MSIX)

AppX packages use the MSIX format for the Microsoft Store and sideloading. Desktop Bridge apps run with full trust (`runFullTrust`), so they are **not sandboxed**. They use the [store build pipeline](../sandboxing.md#windows-appx) automatically.

!!! warning "Developer Mode required for local builds"
    Building AppX/MSIX packages locally requires **Windows Developer Mode** to be enabled. Without it, the build will fail. Go to **Settings → System → For developers** and enable **Developer Mode**. This is not needed in CI (GitHub-hosted runners have it enabled by default).

```kotlin
windows {
    appx {
        applicationId = "MyApp"
        publisherDisplayName = "My Company"
        displayName = "My App"
        publisher = "CN=D541E802-6D30-446A-864E-2E8ABD2DAA5E"
        identityName = "MyCompany.MyApp"

        // Languages
        languages = listOf("en-US", "fr-FR")

        // Visual
        backgroundColor = "#001F3F"
        showNameOnTiles = true

        // Tile logos (PNG)
        storeLogo.set(project.file("packaging/appx/StoreLogo.png"))
        square44x44Logo.set(project.file("packaging/appx/Square44x44Logo.png"))
        square150x150Logo.set(project.file("packaging/appx/Square150x150Logo.png"))
        wide310x150Logo.set(project.file("packaging/appx/Wide310x150Logo.png"))

        // Windows version range written into AppxManifest.xml.
        // Required by the Store: MinVersion must be > 10.0.17134.0 (Windows 10 1803).
        minVersion = "10.0.17763.0"        // Windows 10 1809 (October 2018 Update)
        maxVersionTested = "10.0.22621.0"  // Windows 11 22H2

        // Store build options
        addAutoLaunchExtension = false
        setBuildNumber = true
    }
}
```

### AppX Logo Requirements

| Asset | Size | Description |
|-------|------|-------------|
| `StoreLogo.png` | 50x50 | Store listing icon |
| `Square44x44Logo.png` | 44x44 | Taskbar icon |
| `Square150x150Logo.png` | 150x150 | Start Menu tile |
| `Wide310x150Logo.png` | 310x150 | Wide Start Menu tile |

### MSIX Bundle (Multi-Architecture)

Create an `.msixbundle` containing both amd64 and arm64 `.appx` files. See [CI/CD](../ci-cd.md#windows-msix-bundle) for the GitHub Actions workflow.

### Submitting to the Microsoft Store

Two requirements are enforced by Partner Center at upload time. Both apply to every `.appx`/`.msix` inside an `.msixbundle` (the bundle is just a container — each contained package carries its own `AppxManifest.xml`, and Partner Center validates each one).

#### 1. Use the identity assigned by Partner Center

`identityName`, `publisher`, and `publisherDisplayName` must match the values shown on your reservation page in Partner Center under **Product identity**. You cannot pick them freely — the Store rejects packages whose manifest identity does not match the reservation.

```kotlin
appx {
    // Package/Identity/Name from Partner Center
    identityName = "Contoso.MyApp-Suffix"
    // Package/Identity/Publisher (CN=<GUID> assigned by the Store)
    publisher = "CN=12345678-90AB-CDEF-1234-567890ABCDEF"
    // Package/Properties/PublisherDisplayName from Partner Center
    publisherDisplayName = "Contoso"
}
```

!!! warning "Sideload vs Store identity"
    For sideloading you can use any `CN=...` that matches your code-signing certificate. For the Store you **must** use the `CN=<GUID>` assigned by Partner Center — the certificate is provided server-side, so the local signing certificate (if any) does not need to match the Store CN.

#### 2. Set `minVersion` above the Store's floor

By default no `MinVersion` is written and the underlying tooling falls back to a value (`10.0.14316.0`) that the Store rejects with:

> *Package acceptance validation error: bundle is not valid. You can't upload msix/msixbundle/msixupload packages that target Windows with a MinVersion <= 10.0.17134.0.*

Always set `minVersion` to at least `10.0.17763.0` (Windows 10 1809) for Store submissions:

```kotlin
appx {
    minVersion = "10.0.17763.0"
    maxVersionTested = "10.0.22621.0"
}
```

| Property | Maps to | Notes |
|----------|---------|-------|
| `minVersion` | `<TargetDeviceFamily MinVersion="…"/>` | Must be `> 10.0.17134.0` for Store uploads. |
| `maxVersionTested` | `<TargetDeviceFamily MaxVersionTested="…"/>` | Highest Windows build you've validated against. |

## Code Signing

See [Code Signing](../code-signing.md#windows) for full details on PFX certificates and Azure Artifact Signing.

```kotlin
windows {
    signing {
        enabled = true
        certificateFile.set(file("certs/certificate.pfx"))
        certificatePassword = providers.environmentVariable("WIN_CSC_KEY_PASSWORD").orNull
        algorithm = SigningAlgorithm.Sha256
        timestampServer = "http://timestamp.digicert.com"
    }
}
```

## File Associations

```kotlin
nativeDistributions {
    // Cross-platform file association
    fileAssociation(
        mimeType = "application/x-myapp",
        extension = "myapp",
        description = "MyApp Document",
    )

    // Windows-specific with icon
    windows {
        fileAssociation(
            mimeType = "application/x-myapp",
            extension = "myapp",
            description = "MyApp Document",
            iconFile = project.file("icons/file.ico"),
        )
    }
}
```

File associations are propagated to NSIS, NSIS Web, MSI, and AppX formats.
