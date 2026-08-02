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

abstract class AbstractPlatformSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    val iconFile: RegularFileProperty = objects.fileProperty()
    var packageVersion: String? = null

    internal val fileAssociations: MutableSet<FileAssociation> = mutableSetOf()

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

    fun signing(fn: Action<MacOSSigningSettings>) {
        fn.execute(signing)
    }

    val notarization: MacOSNotarizationSettings = objects.newInstance(MacOSNotarizationSettings::class.java)

    fun notarization(fn: Action<MacOSNotarizationSettings>) {
        fn.execute(notarization)
    }

    val dmg: DmgSettings = objects.newInstance(DmgSettings::class.java)

    fun dmg(fn: Action<DmgSettings>) {
        fn.execute(dmg)
    }
}

abstract class NativeApplicationMacOSPlatformSettings : AbstractMacOSPlatformSettings()

abstract class JvmMacOSPlatformSettings : AbstractMacOSPlatformSettings() {
    var dockName: String? = null
    var setDockNameSameAsPackageName: Boolean = true

    /**
     * Previously used to enable App Store signing for PKG builds.
     *
     * This property is now ignored — PKG is always treated as an App Store format.
     * Store-specific signing (sandbox entitlements, "3rd Party Mac Developer" certificates,
     * provisioning profiles, `productsign`) is applied automatically when the target format
     * is [TargetFormat.Pkg].
     */
    @Deprecated(
        "PKG is always built for the App Store. This property is ignored and will be removed in a future release.",
        level = DeprecationLevel.WARNING,
    )
    var appStore: Boolean = false
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

    fun appExtensions(fn: Action<MacAppExtensionSettings>) {
        fn.execute(appExtensions)
    }

    internal val infoPlistSettings = InfoPlistSettings()

    fun infoPlist(fn: Action<InfoPlistSettings>) {
        fn.execute(infoPlistSettings)
    }
}

open class InfoPlistSettings {
    var extraKeysRawXml: String? = null
}

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

    val snap: SnapSettings = objects.newInstance(SnapSettings::class.java)

    fun snap(fn: Action<SnapSettings>) {
        fn.execute(snap)
    }

    val flatpak: FlatpakSettings = objects.newInstance(FlatpakSettings::class.java)

    fun flatpak(fn: Action<FlatpakSettings>) {
        fn.execute(flatpak)
    }

    val appImage: AppImageSettings = objects.newInstance(AppImageSettings::class.java)

    fun appImage(fn: Action<AppImageSettings>) {
        fn.execute(appImage)
    }

    val signing: LinuxSigningSettings = objects.newInstance(LinuxSigningSettings::class.java)

    fun signing(fn: Action<LinuxSigningSettings>) {
        fn.execute(signing)
    }
}

abstract class WindowsPlatformSettings : AbstractPlatformSettings() {
    var packageName: String? = null
    var console: Boolean = false
    var dirChooser: Boolean = true
    var perUserInstall: Boolean = false
    var shortcut: Boolean = false
    var menu: Boolean = false
        get() = field || menuGroup != null
    var menuGroup: String? = null
    var upgradeUuid: String? = null
    var msiPackageVersion: String? = null
    var exePackageVersion: String? = null

    val nsis: NsisSettings = objects.newInstance(NsisSettings::class.java)

    fun nsis(fn: Action<NsisSettings>) {
        fn.execute(nsis)
    }

    val appx: AppXSettings = objects.newInstance(AppXSettings::class.java)

    fun appx(fn: Action<AppXSettings>) {
        fn.execute(appx)
    }

    val signing: WindowsSigningSettings = objects.newInstance(WindowsSigningSettings::class.java)

    fun signing(fn: Action<WindowsSigningSettings>) {
        fn.execute(signing)
    }
}
