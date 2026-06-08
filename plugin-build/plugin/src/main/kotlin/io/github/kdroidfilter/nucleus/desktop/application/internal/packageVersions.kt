/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kdroidfilter.nucleus.desktop.application.internal

import io.github.kdroidfilter.nucleus.desktop.application.dsl.JvmApplicationDistributions
import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
import io.github.kdroidfilter.nucleus.internal.utils.OS
import org.gradle.api.provider.Provider

internal fun JvmApplicationContext.packageVersionFor(targetFormat: TargetFormat): Provider<String> =
    project.provider {
        app.nativeDistributions.packageVersionFor(targetFormat)
            ?: project.version.toString().takeIf { it != "unspecified" }
            ?: "1.0.0"
    }

/**
 * Version used when jpackage builds the app-image (`--app-version`).
 *
 * jpackage is the only [io.github.kdroidfilter.nucleus.desktop.application.dsl.PackagingBackend.JPACKAGE]
 * step ([TargetFormat.RawAppImage]) and enforces strict platform version rules — notably Windows
 * rejects SemVer pre-release/build metadata such as `2.3.5-beta.7`. All real installer formats run
 * through electron-builder and keep the full SemVer via [packageVersionFor].
 */
internal fun JvmApplicationContext.jpackageVersionFor(targetFormat: TargetFormat): Provider<String> =
    packageVersionFor(targetFormat).map { it.toJpackageVersion() }

// jpackage rejects SemVer pre-release/build metadata; keep only the MAJOR.MINOR.PATCH core.
private fun String.toJpackageVersion(): String =
    substringBefore('-').substringBefore('+')

@Suppress("CyclomaticComplexMethod") // Exhaustive when on TargetFormat enum
private fun JvmApplicationDistributions.packageVersionFor(targetFormat: TargetFormat): String? {
    val formatSpecificVersion: String? =
        when (targetFormat) {
            TargetFormat.RawAppImage -> null
            TargetFormat.Deb -> linux.debPackageVersion
            TargetFormat.Rpm -> linux.rpmPackageVersion
            TargetFormat.Dmg -> macOS.dmgPackageVersion
            TargetFormat.Pkg -> macOS.pkgPackageVersion
            TargetFormat.Exe -> windows.exePackageVersion
            TargetFormat.Msi -> windows.msiPackageVersion
            TargetFormat.Nsis, TargetFormat.NsisWeb, TargetFormat.Portable,
            TargetFormat.AppX,
            -> windows.exePackageVersion
            TargetFormat.AppImage, TargetFormat.Snap, TargetFormat.Flatpak -> linux.debPackageVersion
            TargetFormat.Zip, TargetFormat.Tar, TargetFormat.SevenZ -> null
        }
    val osSpecificVersion: String? =
        when (targetFormat.targetOS) {
            OS.Linux -> linux.packageVersion
            OS.MacOS -> macOS.packageVersion
            OS.Windows -> windows.packageVersion
        }
    return formatSpecificVersion
        ?: osSpecificVersion
        ?: packageVersion
}

internal fun JvmApplicationContext.packageBuildVersionFor(targetFormat: TargetFormat): Provider<String> =
    project.provider {
        app.nativeDistributions.packageBuildVersionFor(targetFormat)
            // fallback to normal version
            ?: app.nativeDistributions.packageVersionFor(targetFormat)
            ?: project.version.toString().takeIf { it != "unspecified" }
            ?: "1.0.0"
    }

private fun JvmApplicationDistributions.packageBuildVersionFor(targetFormat: TargetFormat): String? {
    if (targetFormat.targetOS != OS.MacOS) return null

    val formatSpecificVersion: String? =
        when (targetFormat) {
            TargetFormat.Dmg -> macOS.dmgPackageBuildVersion
            TargetFormat.Pkg -> macOS.pkgPackageBuildVersion
            else -> null
        }
    val osSpecificVersion: String? = macOS.packageBuildVersion
    return formatSpecificVersion
        ?: osSpecificVersion
}
