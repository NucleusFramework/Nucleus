/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/**
 * macOS PKG installer settings, scoped under `nativeDistributions { macOS { pkg { ... } } }`.
 *
 * A PKG is built for one of two distribution channels, selected by [appStore]:
 * - **Mac App Store** (default): the app goes through the sandboxed pipeline (sandbox entitlements,
 *   "3rd Party Mac Developer" certificates, provisioning profile), the installer is re-signed with
 *   `productsign`, and nothing is notarized — the `.pkg` is uploaded with Transporter.
 * - **Developer ID** (`appStore = false`): the app goes through the same non-sandboxed pipeline as
 *   DMG (Developer ID Application, hardened runtime), electron-builder signs the installer with the
 *   matching "Developer ID Installer" certificate, and `notarizePkg` notarizes and staples the
 *   `.pkg`. This is the channel for MDM deployment (Jamf, …) and manual installs outside the store,
 *   and the only one that accepts [preInstall] / [postInstall] scripts.
 *
 * ```kotlin
 * macOS {
 *     pkg {
 *         appStore = false
 *         preInstall.set(file("packaging/macos/preinstall"))
 *         postInstall.set(file("packaging/macos/postinstall"))
 *     }
 * }
 * ```
 */
@Suppress("AbstractClassCanBeConcreteClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class PkgSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    /**
     * Whether the PKG targets the Mac App Store (`true`, the default) or direct Developer ID
     * distribution (`false`). See the class documentation for what each channel changes.
     */
    var appStore: Boolean = true

    /**
     * Script the macOS Installer runs as root **before** the payload is copied. Staged as the
     * package's top-level `preinstall` script (`pkgbuild --scripts`) whatever the source file is
     * named; it must start with a shebang. Receives the standard Installer arguments: `$1` package
     * path, `$2` install target, `$3` target volume, `$4` startup disk.
     *
     * It runs **once**. electron-builder declares install scripts both per bundle and at the top
     * level, which makes Installer run them twice; Nucleus stages a small entry point that collapses
     * that back to a single call, so the script does not have to be idempotent.
     *
     * Requires `appStore = false`: the Mac App Store rejects installer packages that carry install
     * scripts (validation error 90254).
     */
    val preInstall: RegularFileProperty = objects.fileProperty()

    /** Script the Installer runs as root **after** the payload is copied. Same rules as [preInstall]. */
    val postInstall: RegularFileProperty = objects.fileProperty()

    internal val hasScripts: Boolean
        get() = preInstall.isPresent || postInstall.isPresent
}
