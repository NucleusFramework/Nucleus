/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.dsl

import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import java.io.File
import java.io.Serializable

internal val DEFAULT_RUNTIME_MODULES =
    arrayOf(
        "java.base",
        "java.desktop",
        "java.logging",
        "java.net.http",
        "jdk.accessibility",
        "jdk.crypto.ec",
    )

abstract class JvmApplicationDistributions : AbstractDistributions() {
    @Suppress("DoubleMutabilityForCollection", "SpreadOperator")
    var modules = arrayListOf(*DEFAULT_RUNTIME_MODULES)

    fun modules(vararg modules: String) {
        this.modules.addAll(modules.toList())
    }

    var includeAllModules: Boolean = false

    /** Strip native libraries for non-target platforms from dependency JARs to reduce package size. */
    var cleanupNativeLibs: Boolean = false

    /** Splash screen image filename relative to appResources (e.g. "splash.png"). */
    var splashImage: String? = null

    /** Enable JDK 25+ AOT cache generation for faster application startup. */
    var enableAotCache: Boolean = false

    /**
     * Whether any of the configured target formats require sandboxing
     * (store formats like PKG, AppX, Flatpak) AND are compatible with the current OS.
     */
    internal val hasStoreFormats: Boolean
        get() = targetFormats.any { it.isStoreFormat && it.isCompatibleWithCurrentOS }

    val linux: LinuxPlatformSettings = objects.newInstance(LinuxPlatformSettings::class.java)

    open fun linux(fn: Action<LinuxPlatformSettings>) {
        fn.execute(linux)
    }

    val macOS: JvmMacOSPlatformSettings = objects.newInstance(JvmMacOSPlatformSettings::class.java)

    open fun macOS(fn: Action<JvmMacOSPlatformSettings>) {
        fn.execute(macOS)
    }

    val windows: WindowsPlatformSettings = objects.newInstance(WindowsPlatformSettings::class.java)

    fun windows(fn: Action<WindowsPlatformSettings>) {
        fn.execute(windows)
    }

    @JvmOverloads
    fun fileAssociation(
        mimeType: String,
        extension: String,
        description: String,
        linuxIconFile: File? = null,
        windowsIconFile: File? = null,
        macOSIconFile: File? = null,
    ) {
        linux.fileAssociation(mimeType, extension, description, linuxIconFile)
        windows.fileAssociation(mimeType, extension, description, windowsIconFile)
        macOS.fileAssociation(mimeType, extension, description, macOSIconFile)
    }

    // --- Publishing ---

    val publish: PublishSettings = objects.newInstance(PublishSettings::class.java)

    fun publish(fn: Action<PublishSettings>) {
        fn.execute(publish)
    }

    // --- Compression level for archive formats ---

    var compressionLevel: CompressionLevel? = null

    // --- Artifact name template (e.g., "\${name}-\${version}-\${arch}.\${ext}") ---

    var artifactName: String = "\${name}-\${version}-\${os}-\${arch}.\${ext}"

    // --- Trusted CA certificates for the bundled JVM ---

    /**
     * CA certificate files (PEM/DER) to import into the bundled JDK's `cacerts` keystore.
     *
     * Example:
     * ```kotlin
     * nativeDistributions {
     *     trustedCertificates.from(files("certs/my-ca.crt", "certs/company-ca.pem"))
     * }
     * ```
     *
     * Each certificate is imported using `keytool -import -trustcacerts`. The alias is
     * derived from the filename (lowercased, non-alphanumeric characters replaced with `-`).
     * Import is idempotent: if an alias already exists it is silently skipped.
     */
    val trustedCertificates: ConfigurableFileCollection = objects.fileCollection()

    // --- URL protocol handlers (deep linking) ---

    val protocols: MutableList<UrlProtocol> = mutableListOf()

    fun protocol(
        name: String,
        vararg schemes: String,
    ) {
        protocols.add(UrlProtocol(name, schemes.toList()))
    }
}

data class UrlProtocol(
    val name: String,
    val schemes: List<String>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
