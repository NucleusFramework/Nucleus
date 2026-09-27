/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import org.gradle.api.logging.Logger
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Generates electron-builder-compatible auto-update metadata (latest-*.yml)
 * for target formats where electron-builder does not produce them natively
 * (e.g. MSI, Portable, AppImage, DEB, RPM, DMG).
 */
internal object UpdateYmlGenerator {
    private const val BUFFER_SIZE = 8192
    private val SKIP_EXTENSIONS = setOf("yml", "yaml", "blockmap", "json")

    /**
     * Generates the auto-update YML file if it does not already exist.
     * When electron-builder natively generates the file (e.g. for NSIS with a publish provider),
     * this is a no-op.
     *
     * @param artifactExtension when set, only files with this extension are listed — the output
     *   directory also holds build leftovers (`nucleus-installer.nsh`, …) that are no artifact.
     */
    fun generateIfMissing(
        outputDir: File,
        ymlFilename: String,
        version: String,
        logger: Logger,
        artifactExtension: String? = null,
    ) {
        val ymlFile = File(outputDir, ymlFilename)
        if (ymlFile.exists()) {
            logger.info("Auto-update metadata already exists: ${ymlFile.name}, skipping generation")
            return
        }

        val candidates = outputDir.listFiles { f ->
            f.isFile &&
                !f.name.startsWith(".") &&
                f.extension.lowercase() !in SKIP_EXTENSIONS &&
                (artifactExtension == null || f.extension.equals(artifactExtension, ignoreCase = true))
        }?.sortedBy { it.name } ?: emptyList()
        val installerFiles = currentArtifacts(candidates, version)

        if (installerFiles.isEmpty()) {
            logger.warn("No installer files found in ${outputDir.absolutePath}, skipping update YML generation")
            return
        }

        val releaseDate = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(Instant.now())

        val filesEntries = buildString {
            for (file in installerFiles) {
                val hash = sha512Base64(file)
                appendLine("  - url: ${file.name}")
                appendLine("    sha512: $hash")
                appendLine("    size: ${file.length()}")
                val blockmap = File(outputDir, "${file.name}.blockmap")
                if (blockmap.exists()) {
                    appendLine("    blockMapSize: ${blockmap.length()}")
                }
            }
        }

        val firstFile = installerFiles.first()
        val firstHash = sha512Base64(firstFile)

        val content = buildString {
            appendLine("version: $version")
            appendLine("files:")
            append(filesEntries)
            appendLine("path: ${firstFile.name}")
            appendLine("sha512: $firstHash")
            appendLine("releaseDate: '$releaseDate'")
        }

        ymlFile.writeText(content)
        logger.lifecycle("Generated auto-update metadata: ${ymlFile.name}")
    }

    /**
     * The artifacts of this packaging run among [candidates]. electron-builder does not clean its
     * output directory, so the installer of a previous version is still there after a version bump;
     * listed first, it would be what every client downloads as the new version. The artifacts whose
     * name carries [version] are kept, or, for an artifact name without a version, the newest one.
     */
    internal fun currentArtifacts(
        candidates: List<File>,
        version: String,
    ): List<File> {
        val versioned = candidates.filter { VERSION_BOUNDARY.replace("{v}", Regex.escape(version)).toRegex().containsMatchIn(it.name) }
        if (versioned.isNotEmpty()) return versioned
        return listOfNotNull(candidates.maxByOrNull { it.lastModified() })
    }

    /** [version] as a whole component of a file name: `1.1.0` must not match in `11.1.0` or `1.1.0.1`. */
    private const val VERSION_BOUNDARY = """(?<![\w.]){v}(?![\w]|\.\d)"""

    private fun sha512Base64(file: File): String {
        val digest = MessageDigest.getInstance("SHA-512")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return Base64.getEncoder().encodeToString(digest.digest())
    }
}
