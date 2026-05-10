package dev.nucleusframework.nucleus.updater.internal

import dev.nucleusframework.nucleus.updater.exception.ParseException

internal data class YamlMetadata(
    val version: String,
    val files: List<YamlFileEntry>,
    val releaseDate: String,
)

internal data class YamlFileEntry(
    val url: String,
    val sha512: String,
    val size: Long,
    val blockMapSize: Long? = null,
)

internal object YamlParser {
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
    fun parse(yaml: String): YamlMetadata {
        var version = ""
        var releaseDate = ""
        val files = mutableListOf<YamlFileEntry>()

        var currentUrl = ""
        var currentSha512 = ""
        var currentSize = 0L
        var currentBlockMapSize: Long? = null
        var inFiles = false
        var inFileEntry = false

        for (rawLine in yaml.lines()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue

            val indent = line.length - line.trimStart().length
            val trimmed = line.trimStart()

            if (indent == 0 && !trimmed.startsWith("-")) {
                // Top-level field — flush any pending file entry
                if (inFileEntry) {
                    files.add(YamlFileEntry(currentUrl, currentSha512, currentSize, currentBlockMapSize))
                    resetEntry()
                    inFileEntry = false
                }
                inFiles = false

                when {
                    trimmed.startsWith("version:") -> version = extractValue(trimmed)
                    trimmed.startsWith("releaseDate:") -> releaseDate = extractValue(trimmed)
                    trimmed.startsWith("files:") -> inFiles = true
                }
            } else if (inFiles) {
                if (trimmed.startsWith("- url:") || trimmed.startsWith("-url:")) {
                    // New file entry — flush previous if any
                    if (inFileEntry) {
                        files.add(YamlFileEntry(currentUrl, currentSha512, currentSize, currentBlockMapSize))
                    }
                    currentUrl = extractValue(trimmed.removePrefix("-").trimStart())
                    currentSha512 = ""
                    currentSize = 0L
                    currentBlockMapSize = null
                    inFileEntry = true
                } else if (inFileEntry) {
                    when {
                        trimmed.startsWith("sha512:") -> currentSha512 = extractValue(trimmed)
                        trimmed.startsWith("size:") -> currentSize = extractValue(trimmed).toLongOrNull() ?: 0L
                        trimmed.startsWith("blockMapSize:") ->
                            currentBlockMapSize = extractValue(trimmed).toLongOrNull()
                    }
                }
            }
        }

        // Flush last file entry
        if (inFileEntry) {
            files.add(YamlFileEntry(currentUrl, currentSha512, currentSize, currentBlockMapSize))
        }

        if (version.isEmpty()) {
            throw ParseException("Missing 'version' field in YAML metadata")
        }

        return YamlMetadata(version, files, releaseDate)
    }

    private fun resetEntry() {
        // No-op: variables are reset in the calling code
    }

    private fun extractValue(field: String): String {
        val colonIndex = field.indexOf(':')
        if (colonIndex < 0) return ""
        return field
            .substring(colonIndex + 1)
            .trim()
            .removeSurrounding("'")
            .removeSurrounding("\"")
    }
}
