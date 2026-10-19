/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

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
    @get:Input
    var enabled: Boolean = false

    /** Certificate file (.pfx/.p12) */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val certificateFile: RegularFileProperty = objects.fileProperty()

    /** Certificate password */
    @get:Input
    @get:Optional
    var certificatePassword: String? = null

    /** Certificate SHA1 thumbprint (for store certs) */
    @get:Input
    @get:Optional
    var certificateSha1: String? = null

    /** Certificate subject name */
    @get:Input
    @get:Optional
    var certificateSubjectName: String? = null

    /** Timestamp server URL */
    @get:Input
    @get:Optional
    var timestampServer: String? = null

    /**
     * Also sign the DLLs bundled with the app — the Java runtime's, Skiko's, the Nucleus natives and
     * those packed inside JARs (signed JARs excepted) — with this certificate. Windows Smart App
     * Control refuses to load an unsigned DLL, whichever process loads it. Binaries that already
     * carry a signature keep it. Only applies when [enabled] is set; the launcher executables are
     * signed regardless. Default: true
     */
    @get:Input
    var signNativeLibraries: Boolean = true

    /** Signing hash algorithm. Default: [SigningAlgorithm.Sha256] */
    @get:Input
    var algorithm: SigningAlgorithm = SigningAlgorithm.Sha256

    /**
     * Publisher name, exactly as in the code signing certificate.
     * Required by electron-builder when signing with Azure Artifact Signing.
     * If unset, falls back to `nativeDistributions.vendor`.
     */
    @get:Input
    @get:Optional
    var publisherName: String? = null

    // --- Azure Artifact Signing ---

    /** Azure tenant ID for Artifact Signing */
    @get:Input
    @get:Optional
    var azureTenantId: String? = null

    /** Azure Artifact Signing endpoint URL */
    @get:Input
    @get:Optional
    var azureEndpoint: String? = null

    /** Azure certificate profile name */
    @get:Input
    @get:Optional
    var azureCertificateProfileName: String? = null

    /** Azure Code Signing account name */
    @get:Input
    @get:Optional
    var azureCodeSigningAccountName: String? = null
}
