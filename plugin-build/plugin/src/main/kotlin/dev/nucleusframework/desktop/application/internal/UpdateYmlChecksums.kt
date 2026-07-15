/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Recomputes and rewrites the checksums that electron-builder auto-update manifests
 * (`latest*.yml`) record for a packaged artifact.
 *
 * Any post-processing that mutates an artifact after electron-builder has already fingerprinted
 * it — notarization stapling, DMG LZMA recompression — invalidates the `sha512`/`size`
 * (and, when the blockmap changes, `blockMapSize`) stored in the manifest. These helpers bring
 * the manifest back in sync so the in-app updater accepts the new artifact.
 */
internal object UpdateYmlChecksums {
    private const val BUFFER_SIZE = 8192

    /** Base64-encoded SHA-512 of [file], matching electron-builder's manifest `sha512` field. */
    fun sha512Base64(file: File): String {
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

    /**
     * Returns [yaml] with the `sha512`/`size` (and top-level `sha512`) of the entry for [fileName]
     * replaced by [newHash]/[newSize].
     *
     * When [newBlockMapSize] is non-null, an existing `blockMapSize` line in the entry is updated;
     * when it is null, any `blockMapSize` line is removed so the updater falls back to a full
     * download instead of chasing a stale/absent blockmap.
     */
    fun updateYamlEntry(
        yaml: String,
        fileName: String,
        newHash: String,
        newSize: Long,
        newBlockMapSize: Long? = null,
    ): String {
        val lines = yaml.lines().toMutableList()
        var i = 0
        var topLevelPath: String? = null

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()

            if (isUrlEntry(trimmed) && extractUrl(trimmed) == fileName) {
                i = updateFileEntryFields(lines, i + 1, newHash, newSize, newBlockMapSize)
                continue
            }

            val isTopLevel = !line.startsWith(" ") && !line.startsWith("\t")
            if (isTopLevel && trimmed.startsWith("path:")) {
                topLevelPath = trimmed.removePrefix("path:").trim()
            }
            if (isTopLevel && trimmed.startsWith("sha512:") && topLevelPath == fileName) {
                lines[i] = "sha512: $newHash"
            }

            i++
        }

        return lines.joinToString("\n")
    }

    private fun isUrlEntry(trimmed: String): Boolean = trimmed.startsWith("- url:") || trimmed.startsWith("-url:")

    private fun extractUrl(trimmed: String): String =
        trimmed
            .removePrefix("-")
            .trimStart()
            .removePrefix("url:")
            .trim()

    private fun isEndOfFileEntry(entryLine: String): Boolean {
        if (isUrlEntry(entryLine)) return true
        if (entryLine.startsWith("blockMapSize:")) return false
        return !entryLine.startsWith(" ") && entryLine.contains(":")
    }

    private fun updateFileEntryFields(
        lines: MutableList<String>,
        startIndex: Int,
        newHash: String,
        newSize: Long,
        newBlockMapSize: Long?,
    ): Int {
        var i = startIndex
        while (i < lines.size) {
            val entryLine = lines[i].trimStart()
            val indent = lines[i].length - lines[i].trimStart().length
            when {
                entryLine.startsWith("sha512:") -> lines[i] = " ".repeat(indent) + "sha512: $newHash"
                entryLine.startsWith("size:") -> lines[i] = " ".repeat(indent) + "size: $newSize"
                entryLine.startsWith("blockMapSize:") -> {
                    if (newBlockMapSize != null) {
                        lines[i] = " ".repeat(indent) + "blockMapSize: $newBlockMapSize"
                    } else {
                        // Blockmap no longer valid: drop the reference so the updater does a full download.
                        lines.removeAt(i)
                        continue
                    }
                }
                isEndOfFileEntry(entryLine) -> break
            }
            i++
        }
        return i
    }
}
