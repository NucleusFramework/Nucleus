/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.internal.utils.OS
import org.gradle.api.provider.Provider

internal fun JvmApplicationContext.packageVersionFor(targetFormat: TargetFormat): Provider<String> =
    project.provider {
        app.nativeDistributions.packageVersionFor(targetFormat)
            ?: project.version.toString().takeIf { it != "unspecified" }
            ?: "1.0.0"
    }

/** The version jpackage stamps into the app image; see [jpackageAppVersion]. */
internal fun JvmApplicationContext.jpackageVersionFor(targetFormat: TargetFormat): Provider<String> =
    packageVersionFor(targetFormat).map { jpackageAppVersion(it, targetFormat.targetOS) }

/**
 * [version] in a form jpackage accepts on [os].
 *
 * jpackage on Windows and macOS takes numeric components only and rejects SemVer pre-release and
 * build metadata (`2.3.5-beta.7` fails with "invalid component [5-beta.7]"), so the suffix is
 * dropped there. jpackage on Linux accepts it, so the version passes through unchanged. Installers
 * are built by electron-builder from the full [packageVersionFor] version, which it converts to
 * each packaging system's form itself.
 */
internal fun jpackageAppVersion(
    version: String,
    os: OS,
): String =
    when (os) {
        OS.Linux -> version
        OS.Windows, OS.MacOS -> version.withoutSemVerSuffix()
    }

/** The `MAJOR.MINOR.PATCH` release part of a SemVer version, without pre-release or build metadata. */
internal fun String.withoutSemVerSuffix(): String = substringBefore('-').substringBefore('+')

@Suppress("CyclomaticComplexMethod") // Exhaustive when on TargetFormat enum
private fun JvmApplicationDistributions.packageVersionFor(targetFormat: TargetFormat): String? {
    val formatSpecificVersion: String? =
        when (targetFormat) {
            TargetFormat.RawAppImage -> null
            TargetFormat.Deb -> linux.debPackageVersion
            TargetFormat.Rpm -> linux.rpmPackageVersion
            TargetFormat.Pacman -> linux.pacmanPackageVersion
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
            // Fall back to the version jpackage stamps as CFBundleShortVersionString:
            // CFBundleVersion must be numeric too.
            ?: jpackageVersionFor(targetFormat).get()
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
