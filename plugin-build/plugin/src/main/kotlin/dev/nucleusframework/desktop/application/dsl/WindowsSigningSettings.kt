/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/**
 * Windows code-signing settings: a certificate file, a certificate from the store (by SHA-1 or subject)
 * or Azure Trusted Signing, applied to the installers and the app image.
 */
abstract class WindowsSigningSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    /** Enable Windows code signing. Default: false */
    var enabled: Boolean = false

    /** Certificate file (.pfx/.p12) */
    val certificateFile: RegularFileProperty = objects.fileProperty()

    /** Certificate password */
    var certificatePassword: String? = null

    /** Certificate SHA1 thumbprint (for store certs) */
    var certificateSha1: String? = null

    /** Certificate subject name */
    var certificateSubjectName: String? = null

    /** Timestamp server URL */
    var timestampServer: String? = null

    /**
     * Also sign the DLLs bundled with the app — the Java runtime's, Skiko's, the Nucleus natives and
     * those packed inside JARs (signed JARs excepted) — with this certificate. Windows Smart App
     * Control refuses to load an unsigned DLL, whichever process loads it. Binaries that already
     * carry a signature keep it. Only applies when [enabled] is set; the launcher executables are
     * signed regardless. Default: true
     */
    var signNativeLibraries: Boolean = true

    /** Signing hash algorithm. Default: [SigningAlgorithm.Sha256] */
    var algorithm: SigningAlgorithm = SigningAlgorithm.Sha256

    /**
     * Publisher name, exactly as in the code signing certificate.
     * Required by electron-builder when signing with Azure Artifact Signing.
     * If unset, falls back to `nativeDistributions.vendor`.
     */
    var publisherName: String? = null

    // --- Azure Artifact Signing ---

    /** Azure tenant ID for Artifact Signing */
    var azureTenantId: String? = null

    /** Azure Artifact Signing endpoint URL */
    var azureEndpoint: String? = null

    /** Azure certificate profile name */
    var azureCertificateProfileName: String? = null

    /** Azure Code Signing account name */
    var azureCodeSigningAccountName: String? = null
}
