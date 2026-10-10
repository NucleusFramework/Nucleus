/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import java.io.File
import javax.inject.Inject

/**
 * Packaging settings shared by every platform: the icon, a platform-specific package version
 * and file associations.
 */
abstract class AbstractPlatformSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    val iconFile: RegularFileProperty = objects.fileProperty()
    var packageVersion: String? = null

    internal val fileAssociations: MutableSet<FileAssociation> = mutableSetOf()

    /**
     * Associates files with [extension] / [mimeType] with the application on this platform,
     * shown with [description] and, optionally, [iconFile].
     */
    @JvmOverloads
    fun fileAssociation(
        mimeType: String,
        extension: String,
        description: String,
        iconFile: File? = null,
    ) {
        fileAssociations.add(FileAssociation(mimeType, extension, description, iconFile))
    }
}

/**
 * macOS packaging settings shared by JVM and Kotlin/Native applications: bundle identity and versions,
 * signing, notarization and the DMG layout.
 */
abstract class AbstractMacOSPlatformSettings : AbstractPlatformSettings() {
    var packageName: String? = null

    /**
     * Name of the `.app` bundle directory, without the `.app` extension.
     *
     * Every macOS artifact — DMG, ZIP, PKG, the raw app image and the GraalVM bundle — ships the
     * bundle under this exact name, so an app installed from one format can be updated from another.
     *
     * Defaults to [AbstractDistributions.appName], falling back to [packageName] and then to the
     * root `packageName`. The value is sanitized the same way electron-builder sanitizes
     * `productName`, so characters that are illegal in a filename are dropped.
     *
     * Note that this only renames the bundle directory: the launcher stays at
     * `Contents/MacOS/<packageName>` and `CFBundleName` keeps using `appName`.
     */
    var bundleName: String? = null

    var packageBuildVersion: String? = null
    var dmgPackageVersion: String? = null
    var dmgPackageBuildVersion: String? = null
    var appCategory: String? = null
    var minimumSystemVersion: String? = null
    var installationPath: String? = null
    var layeredIconDir: DirectoryProperty = objects.directoryProperty()

    /**
     * An application's unique identifier across Apple's ecosystem.
     *
     * May only contain alphanumeric characters (A-Z,a-z,0-9), hyphen (-) and period (.) characters
     *
     * Use of a reverse DNS notation (e.g. com.mycompany.myapp) is recommended.
     */
    var bundleID: String? = null

    val signing: MacOSSigningSettings = objects.newInstance(MacOSSigningSettings::class.java)

    /** Configures macOS code signing. */
    fun signing(fn: Action<MacOSSigningSettings>) {
        fn.execute(signing)
    }

    val notarization: MacOSNotarizationSettings = objects.newInstance(MacOSNotarizationSettings::class.java)

    /** Configures macOS notarization. */
    fun notarization(fn: Action<MacOSNotarizationSettings>) {
        fn.execute(notarization)
    }

    val dmg: DmgSettings = objects.newInstance(DmgSettings::class.java)

    /** Configures the DMG installer. */
    fun dmg(fn: Action<DmgSettings>) {
        fn.execute(dmg)
    }
}

/** macOS packaging settings of a Kotlin/Native application. */
abstract class NativeApplicationMacOSPlatformSettings : AbstractMacOSPlatformSettings()

/**
 * macOS packaging settings of a JVM application: on top of the shared ones, the Dock name, PKG channel,
 * sandboxing entitlements and provisioning, launch agents, app extensions and extra `Info.plist` keys.
 */
abstract class JvmMacOSPlatformSettings : AbstractMacOSPlatformSettings() {
    var dockName: String? = null
    var setDockNameSameAsPackageName: Boolean = true

    /**
     * PKG installer settings: distribution channel (Mac App Store or Developer ID) and install
     * scripts. See [PkgSettings].
     */
    val pkg: PkgSettings = objects.newInstance(PkgSettings::class.java)

    /** Configures the PKG installer, see [PkgSettings]. */
    fun pkg(fn: Action<PkgSettings>) {
        fn.execute(pkg)
    }

    /**
     * Whether a PKG targets the Mac App Store. Alias of `pkg { appStore = ... }`, see
     * [PkgSettings.appStore].
     *
     * Deprecated at ERROR level on purpose: this property used to be **ignored** and defaulted to
     * `false`, so silently aliasing it would flip an existing `appStore = false` build from the Mac
     * App Store to the Developer ID channel — a different pipeline, a different certificate and a
     * different installer signature. Migrating is a one-line edit that has to be deliberate.
     */
    @Deprecated(
        "Use pkg { appStore = ... }. Note the meaning changed: this property was previously ignored " +
            "(PKG was always App Store), so review which channel you want before migrating.",
        ReplaceWith("pkg.appStore"),
        level = DeprecationLevel.ERROR,
    )
    var appStore: Boolean
        get() = pkg.appStore
        set(value) {
            pkg.appStore = value
        }
    val entitlementsFile: RegularFileProperty = objects.fileProperty()
    val runtimeEntitlementsFile: RegularFileProperty = objects.fileProperty()
    var pkgPackageVersion: String? = null
    var pkgPackageBuildVersion: String? = null

    val provisioningProfile: RegularFileProperty = objects.fileProperty()
    val runtimeProvisioningProfile: RegularFileProperty = objects.fileProperty()

    /**
     * Target macOS SDK version to set in the app launcher's Mach-O headers via vtool.
     * This allows AppKit to enable features gated behind a specific SDK version
     * (e.g. Liquid Glass requires SDK 26.0).
     *
     * Set to null to disable patching. Defaults to "26.0".
     * Only effective on macOS; ignored on other platforms.
     */
    var macOsSdkVersion: String? = "26.0"

    /**
     * Configures launch agent plists to embed in the macOS app bundle
     * at `Contents/Library/LaunchAgents/`.
     *
     * These agents are registered at runtime via `SMAppService.agent(plistName:)`.
     *
     * ```kotlin
     * macOS {
     *     launchAgents {
     *         agent("com.myapp.sync") {
     *             bundleProgram("Contents/MacOS/MyApp")
     *             arguments("--sync")
     *             startInterval(900)
     *         }
     *     }
     * }
     * ```
     */
    val launchAgents: LaunchAgentSettings = LaunchAgentSettings()

    /** Configures [launchAgents]. */
    fun launchAgents(fn: Action<LaunchAgentSettings>) {
        fn.execute(launchAgents)
    }

    /**
     * Configures macOS app extensions (`.appex`) to embed under `Contents/PlugIns/`,
     * each signed with its own entitlements and provisioning profile.
     *
     * ```kotlin
     * macOS {
     *     appExtensions {
     *         extension("NetworkFilter") {
     *             appex(file("build/NetworkExtension/NetworkFilter.appex"))
     *             entitlements(file("packaging/networkextension.entitlements"))
     *             provisioningProfile(file("packaging/NetworkFilter.provisionprofile"))
     *         }
     *     }
     * }
     * ```
     */
    val appExtensions: MacAppExtensionSettings = MacAppExtensionSettings()

    /** Configures [appExtensions]. See [MacAppExtensionSettings] for the caveats. */
    fun appExtensions(fn: Action<MacAppExtensionSettings>) {
        fn.execute(appExtensions)
    }

    internal val infoPlistSettings = InfoPlistSettings()

    /** Configures extra keys added to the bundle's `Info.plist`. */
    fun infoPlist(fn: Action<InfoPlistSettings>) {
        fn.execute(infoPlistSettings)
    }
}

/** Extra `Info.plist` content: [extraKeysRawXml] is inserted verbatim into the generated plist dictionary. */
open class InfoPlistSettings {
    var extraKeysRawXml: String? = null
}

/**
 * Linux packaging settings: desktop entry, package metadata and maintainer scripts for deb / rpm / pacman,
 * and the Snap, Flatpak and AppImage targets.
 */
abstract class LinuxPlatformSettings : AbstractPlatformSettings() {
    var shortcut: Boolean = false

    /**
     * Value for StartupWMClass in desktop entry.
     *
     * If null, Nucleus derives a default from `mainClass` by replacing dots with hyphens.
     */
    var startupWMClass: String? = null
    var packageName: String? = null
    var appRelease: String? = null
    var appCategory: String? = null
    var debMaintainer: String? = null
    var menuGroup: String? = null
    var rpmLicenseType: String? = null
    var debPackageVersion: String? = null
    var rpmPackageVersion: String? = null
    var pacmanPackageVersion: String? = null

    /** Additional Debian dependencies for .deb packages. */
    var debDepends: List<String> = emptyList()

    /** Additional RPM requires for .rpm packages. */
    var rpmRequires: List<String> = emptyList()

    /** Additional pacman dependencies for .pacman packages. */
    var pacmanDepends: List<String> = emptyList()

    /**
     * User after-install script concatenated after Nucleus's own template (desktop
     * integration, AppArmor, optional polkit silent-update helper). The combined
     * script is then run through electron-builder, which substitutes macros such as
     * `${sanitizedProductName}` and `${executable}` — but **only when the token is
     * single-quoted**, e.g. `'${executable}-daemon.service'`. A double-quoted token
     * (`"${executable}"`) is left as literal, unsubstituted text, so a systemd
     * `systemctl enable "${executable}.service"` line will silently no-op instead
     * of failing loudly.
     */
    val afterInstall: RegularFileProperty = objects.fileProperty()

    /**
     * User after-remove script concatenated after Nucleus's own template (polkit
     * policy cleanup when silent update is enabled). Same electron-builder macro
     * substitution rules as [afterInstall] apply: single-quoted `${executable}`
     * tokens are substituted, double-quoted ones are not.
     *
     * On Pacman, none of [afterInstall]/[afterRemove]/[beforeInstall]/[beforeRemove]
     * run at all during an upgrade (pacman replacing an already-installed package with
     * a newer version) — Pacman's `.INSTALL` only calls `pre_upgrade`/`post_upgrade`
     * for that, and unlike Deb/RPM there is no fallback to the install/remove
     * scriptlets when those are undefined. See [afterUpgrade]/[beforeUpgrade].
     */
    val afterRemove: RegularFileProperty = objects.fileProperty()

    /**
     * User before-install script passed raw to fpm (`--before-install`) — it is
     * **not** run through electron-builder, so none of the `${sanitizedProductName}`
     * / `${executable}` macro substitution that [afterInstall] gets applies here.
     * Hardcode unit/path names instead of relying on those tokens. Runs as root
     * before the payload is unpacked — stop a packaged systemd service here so
     * binaries in `/opt` can be replaced.
     *
     * Not called on a Pacman upgrade — see [afterRemove] and [beforeUpgrade].
     */
    val beforeInstall: RegularFileProperty = objects.fileProperty()

    /**
     * User before-remove script passed raw to fpm (`--before-remove`) — like
     * [beforeInstall], this gets no electron-builder macro substitution; hardcode
     * unit/path names. Runs as root before the payload is deleted.
     *
     * Not called on a Pacman upgrade — see [afterRemove] and [beforeUpgrade].
     */
    val beforeRemove: RegularFileProperty = objects.fileProperty()

    /**
     * User after-upgrade script passed raw to fpm (`--after-upgrade`), like
     * [beforeInstall] this gets no electron-builder macro substitution. On Pacman,
     * this is the *only* hook that runs when pacman replaces an already-installed
     * package with a newer version — [afterInstall] is skipped entirely in that case.
     * If a systemd service (or anything else set up in [afterInstall]) needs to keep
     * working across upgrades, register/restart it here too, not just in
     * [afterInstall]. Ignored for Deb/RPM, whose install scriptlets already fire on
     * both fresh installs and upgrades.
     */
    val afterUpgrade: RegularFileProperty = objects.fileProperty()

    /**
     * User before-upgrade script passed raw to fpm (`--before-upgrade`). See
     * [afterUpgrade] — on Pacman this is the only pre-payload-swap hook that runs
     * during an upgrade; [beforeInstall] is skipped entirely in that case.
     */
    val beforeUpgrade: RegularFileProperty = objects.fileProperty()

    val snap: SnapSettings = objects.newInstance(SnapSettings::class.java)

    /** Configures the Snap package. */
    fun snap(fn: Action<SnapSettings>) {
        fn.execute(snap)
    }

    val flatpak: FlatpakSettings = objects.newInstance(FlatpakSettings::class.java)

    /** Configures the Flatpak package. */
    fun flatpak(fn: Action<FlatpakSettings>) {
        fn.execute(flatpak)
    }

    val appImage: AppImageSettings = objects.newInstance(AppImageSettings::class.java)

    /** Configures the AppImage package. */
    fun appImage(fn: Action<AppImageSettings>) {
        fn.execute(appImage)
    }

    val signing: LinuxSigningSettings = objects.newInstance(LinuxSigningSettings::class.java)

    /** Configures Linux package signing. */
    fun signing(fn: Action<LinuxSigningSettings>) {
        fn.execute(signing)
    }
}

/**
 * Windows packaging settings: launcher, shortcuts and upgrade code, plus the NSIS, MSI, AppX and
 * portable targets and code signing.
 */
abstract class WindowsPlatformSettings : AbstractPlatformSettings() {
    var packageName: String? = null
    var console: Boolean = false
    var dirChooser: Boolean = true

    @Deprecated(
        "Use msi { perMachine = ... } instead. Note the inverted meaning: " +
            "perUserInstall = true is equivalent to msi.perMachine = false.",
    )
    var perUserInstall: Boolean = false
    var shortcut: Boolean = false
    var menu: Boolean = false
        get() = field || menuGroup != null
    var menuGroup: String? = null
    var upgradeUuid: String? = null
    var msiPackageVersion: String? = null
    var exePackageVersion: String? = null

    val nsis: NsisSettings = objects.newInstance(NsisSettings::class.java)

    /** Configures the NSIS installer. */
    fun nsis(fn: Action<NsisSettings>) {
        fn.execute(nsis)
    }

    val msi: MsiSettings = objects.newInstance(MsiSettings::class.java)

    /** Configures the MSI installer. */
    fun msi(fn: Action<MsiSettings>) {
        fn.execute(msi)
    }

    val appx: AppXSettings = objects.newInstance(AppXSettings::class.java)

    /** Configures the AppX / MSIX package. */
    fun appx(fn: Action<AppXSettings>) {
        fn.execute(appx)
    }

    val portable: PortableSettings = objects.newInstance(PortableSettings::class.java)

    /** Configures the portable executable. */
    fun portable(fn: Action<PortableSettings>) {
        fn.execute(portable)
    }

    val signing: WindowsSigningSettings = objects.newInstance(WindowsSigningSettings::class.java)

    /** Configures Windows code signing. */
    fun signing(fn: Action<WindowsSigningSettings>) {
        fn.execute(signing)
    }
}
