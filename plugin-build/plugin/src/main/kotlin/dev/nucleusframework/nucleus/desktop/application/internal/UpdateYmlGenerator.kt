/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.internal

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
     * When electron-builder natively generates the file (e.g. for NSIS), this is a no-op.
     */
    fun generateIfMissing(
        outputDir: File,
        ymlFilename: String,
        version: String,
        logger: Logger,
    ) {
        val ymlFile = File(outputDir, ymlFilename)
        if (ymlFile.exists()) {
            logger.info("Auto-update metadata already exists: ${ymlFile.name}, skipping generation")
            return
        }

        val installerFiles = outputDir.listFiles { f ->
            f.isFile &&
                !f.name.startsWith(".") &&
                f.extension.lowercase() !in SKIP_EXTENSIONS
        }?.sortedBy { it.name }.orEmpty()

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
