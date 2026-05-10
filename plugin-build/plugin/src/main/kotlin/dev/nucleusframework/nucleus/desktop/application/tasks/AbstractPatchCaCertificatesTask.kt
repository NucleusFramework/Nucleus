/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.tasks

import dev.nucleusframework.nucleus.desktop.tasks.AbstractNucleusTask
import dev.nucleusframework.nucleus.internal.utils.notNullProperty
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.security.MessageDigest

private const val ALIAS_NAME_MAX_LENGTH = 32
private const val ALIAS_HASH_LENGTH = 8

/**
 * Copies the JLink runtime image and imports CA certificates into its `cacerts` keystore.
 *
 * This task produces a patched copy of the runtime image in [destinationDir], so the original
 * [runtimeImageDir] (output of `createRuntimeImage`) is never modified in-place. The copy is
 * used as the runtime by `createDistributable` and `createSandboxedDistributable`.
 *
 * Import is idempotent: if an alias already exists in `cacerts` the entry is silently skipped.
 */
@DisableCachingByDefault(because = "Copying the JVM runtime image is not worth caching")
abstract class AbstractPatchCaCertificatesTask : AbstractNucleusTask() {
    /** Source JLink runtime image directory (output of `createRuntimeImage`). */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeImageDir: DirectoryProperty

    /** CA certificate files (PEM or DER format) to import into `cacerts`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val certificates: ConfigurableFileCollection

    /** Destination directory for the patched runtime image copy. */
    @get:OutputDirectory
    abstract val destinationDir: DirectoryProperty

    /** JDK home used to locate the `keytool` binary. Defaults to the current JVM. */
    @get:Internal
    val javaHome: Property<String> =
        objects.notNullProperty<String>().apply {
            set(providers.systemProperty("java.home"))
        }

    @TaskAction
    fun execute() {
        val sourceDir = runtimeImageDir.get().asFile
        val destDir = destinationDir.get().asFile

        copyRuntime(sourceDir, destDir)

        val cacertsFile = File(destDir, "lib/security/cacerts")
        if (!cacertsFile.exists()) {
            throw GradleException("[caCerts] cacerts not found: ${cacertsFile.absolutePath}")
        }

        val certFiles = certificates.files.filter { it.exists() }
        if (certFiles.isEmpty()) {
            logger.lifecycle("[caCerts] No certificate files to import")
            return
        }

        val keytool = resolveKeytool()
        for (cert in certFiles) {
            val alias = certAlias(cert)
            importCertificate(keytool, cert, alias, cacertsFile)
        }
    }

    private fun copyRuntime(
        source: File,
        dest: File,
    ) {
        dest.deleteRecursively()
        source.copyRecursively(dest, overwrite = true)
        // Restore executable bits on Unix (copyRecursively preserves them on JVM 8+,
        // but we re-apply to be safe for native binaries inside the runtime).
        if (!System.getProperty("os.name").lowercase().contains("windows")) {
            dest
                .walkTopDown()
                .filter { it.isFile && source.resolve(it.relativeTo(dest)).canExecute() }
                .forEach { it.setExecutable(true, false) }
        }
    }

    /**
     * Builds a unique, human-readable alias for a certificate file.
     *
     * Format: `<sanitized-filename>-<first-8-chars-of-sha256>`.
     * The hash suffix guarantees uniqueness even when multiple files share the same
     * name (e.g. two different `ca.crt` from different ISP directories).
     *
     * Examples:
     *   - `netfree-ca.crt`  → `netfree-ca-3a1f8b2c`
     *   - `bezeq/ca.crt`    → `ca-7d4e09a1`
     *   - `partner/ca.crt`  → `ca-f2c51b88`
     */
    private fun certAlias(cert: File): String {
        val namePart =
            cert.nameWithoutExtension
                .lowercase()
                .replace(Regex("[^a-z0-9_-]"), "-")
                .take(ALIAS_NAME_MAX_LENGTH)
        val hashPart =
            MessageDigest
                .getInstance("SHA-256")
                .digest(cert.readBytes())
                .joinToString("") { "%02x".format(it) }
                .take(ALIAS_HASH_LENGTH)
        return "$namePart-$hashPart"
    }

    private fun resolveKeytool(): String {
        val jh = javaHome.get()
        val exe = if (System.getProperty("os.name").lowercase().contains("windows")) "keytool.exe" else "keytool"
        val keytool = File(jh, "bin/$exe")
        return if (keytool.exists()) keytool.absolutePath else "keytool"
    }

    private fun importCertificate(
        keytool: String,
        cert: File,
        alias: String,
        cacerts: File,
    ) {
        logger.lifecycle("[caCerts] Importing ${cert.name} as alias '$alias'")
        val process =
            ProcessBuilder(
                keytool,
                "-import",
                "-trustcacerts",
                "-alias",
                alias,
                "-keystore",
                cacerts.absolutePath,
                "-storepass",
                "changeit",
                "-noprompt",
                "-file",
                cert.absolutePath,
            ).redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        when {
            exitCode == 0 ->
                logger.lifecycle("[caCerts] Imported ${cert.name}")
            output.contains("already exists") ->
                logger.lifecycle("[caCerts] Alias '$alias' already exists, skipping ${cert.name}")
            else ->
                throw GradleException("[caCerts] keytool failed for ${cert.name} (exit $exitCode):\n$output")
        }
    }
}
