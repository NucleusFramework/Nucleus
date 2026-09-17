/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.AbstractDistributions
import dev.nucleusframework.desktop.application.dsl.AbstractMacOSPlatformSettings
import java.io.File
import java.nio.file.Files
import java.text.Normalizer

/**
 * Last-resort bundle name, used when every candidate sanitizes down to an empty string
 * (e.g. `packageName = "..."`, which `sanitize-filename` strips entirely).
 */
private const val FALLBACK_BUNDLE_NAME = "app"

/**
 * Resolves the macOS `.app` bundle directory name from a DSL configuration.
 *
 * Single entry point for every caller — the JVM pipeline, the Kotlin/Native pipeline and the
 * configuration-time validation — so the precedence can never drift between them.
 */
internal fun resolveMacBundleName(
    distributions: AbstractDistributions,
    macOS: AbstractMacOSPlatformSettings,
    projectName: String,
): String =
    resolveMacBundleName(
        bundleName = macOS.bundleName,
        appName = distributions.appName,
        packageName = macOS.packageName ?: distributions.packageName,
        fallback = projectName,
    )

/**
 * Resolves the macOS `.app` bundle directory name (without the `.app` suffix).
 *
 * The same value must be used by every macOS packaging backend. electron-builder stages the bundle
 * inside a DMG under `${productFilename}.app` while its ZIP target archives the prepackaged
 * directory as-is, so the two artifacts only agree when the prepackaged directory is already named
 * `${productFilename}.app`. Feeding this single resolution into jpackage's output, the GraalVM
 * bundle and electron-builder's `productName` keeps that invariant true by construction — an app
 * installed from the DMG can then be updated from the ZIP and vice versa.
 *
 * The result is normalized to NFD because a DMG carries an HFS+ volume, and HFS+ decomposes every
 * file name it stores. Naming the bundle in NFD up front is what makes the DMG entry and the ZIP
 * entry — which preserves whatever the build produced — byte-identical rather than merely equivalent.
 *
 * Precedence: explicit `macOS.bundleName` > `appName` > `macOS.packageName` / `packageName` >
 * [fallback] (normally the Gradle project name).
 */
internal fun resolveMacBundleName(
    bundleName: String?,
    appName: String?,
    packageName: String?,
    fallback: String,
): String =
    macBundleNameCandidates(bundleName, appName, packageName, fallback)
        .firstNotNullOfOrNull { candidate ->
            sanitizeFileName(Normalizer.normalize(candidate, Normalizer.Form.NFD)).takeIf { it.isNotBlank() }
        }
        ?: FALLBACK_BUNDLE_NAME

/** Bundle name candidates in precedence order, blanks removed. */
private fun macBundleNameCandidates(
    bundleName: String?,
    appName: String?,
    packageName: String?,
    fallback: String,
): List<String> = listOfNotNull(bundleName, appName, packageName, fallback).filter { it.isNotBlank() }

/**
 * Renames the macOS `.app` bundle [from] jpackage's output name [to] the resolved bundle name.
 *
 * macOS volumes are case- and normalization-insensitive, so when the two names differ only in case
 * or in Unicode normalization, [to] already resolves to the very bundle jpackage just produced.
 * Deleting it to make room would erase the app image, which is why the existing destination is only
 * cleared when it is a genuinely different directory. A plain rename still rewrites the stored name
 * to the requested form in both cases.
 *
 * Returns true when the bundle was renamed, false when there was nothing to do.
 */
internal fun renameMacAppBundle(
    from: File,
    to: File,
): Boolean {
    if (!from.isDirectory) return false
    if (from.absolutePath == to.absolutePath) return false

    val sameEntry = to.exists() && Files.isSameFile(from.toPath(), to.toPath())
    if (to.exists() && !sameEntry) {
        to.deleteRecursively()
    }
    if (!sameEntry) {
        if (!from.renameTo(to)) {
            error("Unable to rename the app bundle: ${from.absolutePath} -> ${to.absolutePath}")
        }
        return true
    }

    // `rename(2)` succeeds without doing anything when both paths resolve to the same entry, which
    // is what an insensitive volume reports for a case- or normalization-only difference. Going
    // through a temporary name is how the requested spelling actually reaches the volume.
    val staging = File(to.parentFile, ".nucleus-bundle-rename-${System.nanoTime()}")
    if (!from.renameTo(staging)) {
        error("Unable to rename the app bundle: ${from.absolutePath} -> ${staging.absolutePath}")
    }
    if (!staging.renameTo(to)) {
        staging.renameTo(from)
        error("Unable to rename the app bundle: ${from.absolutePath} -> ${to.absolutePath}")
    }
    return true
}

/**
 * Reports configurations where the resolved macOS bundle name is not the one the build script most
 * likely intended, so the ambiguity is settled explicitly via `macOS.bundleName` instead of silently.
 *
 * Two cases are reported:
 *  - `macOS.packageName` is set but loses to `appName`. Before the bundle name was unified, that
 *    property controlled the `.app` directory name for the raw app image, so a project relying on it
 *    would see the bundle renamed.
 *  - the requested name contains characters that are illegal in a filename and had to be stripped.
 */
internal fun macBundleNameWarnings(
    bundleName: String?,
    appName: String?,
    macPackageName: String?,
    packageName: String?,
    resolved: String,
): List<String> =
    buildList {
        val explicitBundleName = bundleName?.takeIf { it.isNotBlank() }
        val requested =
            explicitBundleName
                ?: appName?.takeIf { it.isNotBlank() }
                ?: macPackageName?.takeIf { it.isNotBlank() }
                ?: packageName?.takeIf { it.isNotBlank() }
        // Compared in NFD: the resolved name is decomposed to match what HFS+ stores, and that
        // difference alone must not be reported as a sanitization.
        if (requested != null && Normalizer.normalize(requested, Normalizer.Form.NFD) != resolved) {
            add(
                "w: macOS bundle name \"$requested\" contains characters that are illegal in a file name; " +
                    "the .app bundle will be named \"$resolved.app\". " +
                    "Set macOS.bundleName to choose the name explicitly.",
            )
        }
        val macName = macPackageName?.takeIf { it.isNotBlank() }
        if (explicitBundleName == null && macName != null &&
            Normalizer.normalize(macName, Normalizer.Form.NFD) != resolved
        ) {
            add(
                "w: macOS.packageName (\"$macName\") no longer names the .app bundle directory; " +
                    "every macOS artifact now ships \"$resolved.app\" so the DMG and the ZIP stay " +
                    "interchangeable for auto-update. Set macOS.bundleName = \"$macName\" to keep the " +
                    "previous bundle name.",
            )
        }
    }

// Port of the npm `sanitize-filename` package (v1.6.x), which electron-builder applies to
// `productName` to derive `AppInfo.productFilename`. Keeping the two in sync matters: the DMG target
// names the staged bundle after `productFilename`, so any divergence here reintroduces the very
// desynchronization this resolution exists to prevent.
private val ILLEGAL_CHARS = Regex("""[/?<>\\:*|"]""")
private val CONTROL_CHARS = Regex("[\\x00-\\x1F\\x80-\\x9F]")
private val ONLY_DOTS = Regex("""^\.+$""")
private val WINDOWS_RESERVED = Regex("""^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$""", RegexOption.IGNORE_CASE)
private val WINDOWS_TRAILING = Regex("""[. ]+$""")
private const val MAX_FILE_NAME_BYTES = 255
private const val UTF8_CONTINUATION_MASK = 0xC0
private const val UTF8_CONTINUATION_MARKER = 0x80

/**
 * Kotlin equivalent of electron-builder's `sanitizeFileName`, used to derive `productFilename`.
 *
 * Unlike the npm original this is idempotent: truncation can expose a trailing dot or space that the
 * original would leave in place, and electron-builder sanitizes our `productName` a second time on
 * its way to `productFilename`. A name that is not a fixed point here would come back out of
 * electron-builder shorter than the directory we named, silently breaking the invariant.
 */
internal fun sanitizeFileName(input: String): String {
    val sanitized =
        input
            .replace(ILLEGAL_CHARS, "")
            .replace(CONTROL_CHARS, "")
            .replace(ONLY_DOTS, "")
            .replace(WINDOWS_RESERVED, "")
            .replace(WINDOWS_TRAILING, "")
    return truncateUtf8Bytes(sanitized, MAX_FILE_NAME_BYTES).replace(WINDOWS_TRAILING, "")
}

/** Truncates to at most [maxBytes] UTF-8 bytes without splitting a multi-byte character. */
private fun truncateUtf8Bytes(
    value: String,
    maxBytes: Int,
): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return value
    var end = maxBytes
    while (end > 0 && (bytes[end].toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_MARKER) {
        end--
    }
    return String(bytes, 0, end, Charsets.UTF_8)
}
