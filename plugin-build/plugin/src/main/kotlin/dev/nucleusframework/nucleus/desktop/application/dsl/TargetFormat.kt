/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.dsl

import dev.nucleusframework.nucleus.internal.utils.OS
import dev.nucleusframework.nucleus.internal.utils.currentOS

enum class PackagingBackend {
    /** App-image creation only (jpackage). */
    JPACKAGE,

    /** Full packaging via electron-builder --prepackaged. */
    ELECTRON_BUILDER,
}

enum class TargetFormat(
    internal val id: String,
    internal val targetOS: OS,
    val backend: PackagingBackend,
) {
    // --- Formats using jpackage (app-image only) ---
    RawAppImage("app-image", currentOS, PackagingBackend.JPACKAGE),

    // --- Existing formats migrated to electron-builder ---
    Pkg("pkg", OS.MacOS, PackagingBackend.ELECTRON_BUILDER),
    Deb("deb", OS.Linux, PackagingBackend.ELECTRON_BUILDER),
    Rpm("rpm", OS.Linux, PackagingBackend.ELECTRON_BUILDER),
    Dmg("dmg", OS.MacOS, PackagingBackend.ELECTRON_BUILDER),
    Exe("exe", OS.Windows, PackagingBackend.ELECTRON_BUILDER),
    Msi("msi", OS.Windows, PackagingBackend.ELECTRON_BUILDER),

    // --- New formats (electron-builder only) ---
    Nsis("nsis", OS.Windows, PackagingBackend.ELECTRON_BUILDER),
    NsisWeb("nsis-web", OS.Windows, PackagingBackend.ELECTRON_BUILDER),
    Portable("portable", OS.Windows, PackagingBackend.ELECTRON_BUILDER),
    AppX("appx", OS.Windows, PackagingBackend.ELECTRON_BUILDER),
    AppImage("AppImage", OS.Linux, PackagingBackend.ELECTRON_BUILDER),
    Snap("snap", OS.Linux, PackagingBackend.ELECTRON_BUILDER),
    Flatpak("flatpak", OS.Linux, PackagingBackend.ELECTRON_BUILDER),
    Zip("zip", currentOS, PackagingBackend.ELECTRON_BUILDER),
    Tar("tar.gz", currentOS, PackagingBackend.ELECTRON_BUILDER),
    SevenZ("7z", currentOS, PackagingBackend.ELECTRON_BUILDER),
    ;

    val isCompatibleWithCurrentOS: Boolean by lazy { isCompatibleWith(currentOS) }

    /** Whether this format is a store format that requires sandboxing (App Store, Windows Store, Flatpak). */
    val isStoreFormat: Boolean
        get() = this in setOf(Pkg, AppX, Flatpak)

    /**
     * Whether this format supports auto-update but electron-builder does not generate latest-*.yml for it.
     * electron-builder natively generates yml for: NSIS, NSIS-Web, DMG, AppImage, DEB, RPM.
     * This property is true only for formats that need the plugin to generate it: MSI and Portable.
     */
    val needsPluginUpdateYml: Boolean
        get() = this == Msi || this == Portable

    /** Returns the auto-update YML filename for this format and channel. */
    fun updateYmlFilename(channel: ReleaseChannel): String {
        val prefix = channel.id
        val suffix = when (targetOS) {
            OS.Windows -> ""
            OS.MacOS -> "-mac"
            OS.Linux -> "-linux"
        }
        return "$prefix$suffix.yml"
    }

    internal fun isCompatibleWith(os: OS): Boolean = os == targetOS

    val outputDirName: String
        get() = if (this == RawAppImage) "app" else id

    val fileExt: String
        get() {
            check(this != RawAppImage) { "$this cannot have a file extension" }
            return ".$id"
        }

    /**
     * The electron-builder target name used in CLI arguments.
     * Maps this format to the target identifier expected by electron-builder.
     */
    internal val electronBuilderTarget: String
        get() =
            when (this) {
                Exe, Nsis -> "nsis"
                NsisWeb -> "nsis-web"
                Tar -> "tar.gz"
                SevenZ -> "7z"
                RawAppImage -> error("RawAppImage uses jpackage, not electron-builder")
                else -> id
            }
}
