package dev.nucleusframework.updater.internal

import dev.nucleusframework.core.runtime.Platform

internal object FileSelector {
    // Formats that share .exe and need suffix-based matching in the filename.
    // The plugin adds -nsis, -nsis-web, -portable suffixes to disambiguate.
    private val SUFFIX_FORMATS =
        mapOf(
            "exe" to "-nsis.",
            "nsis" to "-nsis.",
            "nsis-web" to "-nsis.", // nsis-web installs via NSIS, update with full NSIS installer
            "portable" to "-portable.",
        )

    // Formats matched by file extension only (no ambiguity).
    private val EXTENSION_FORMATS =
        mapOf(
            "exe" to ".exe",
            "deb" to ".deb",
            "rpm" to ".rpm",
            "snap" to ".snap",
            "flatpak" to ".flatpak",
            "dmg" to ".dmg",
            "msi" to ".msi",
            "appx" to ".appx",
            "zip" to ".zip",
            "tar.gz" to ".tar.gz",
            "tar" to ".tar.gz",
            "7z" to ".7z",
            "appimage" to ".appimage",
        )

    private val X64_PATTERNS = listOf("amd64", "x64", "x86_64")
    private val ARM64_PATTERNS = listOf("arm64", "aarch64")

    fun select(
        files: List<YamlFileEntry>,
        platform: Platform,
        arch: Arch,
        format: String?,
    ): YamlFileEntry? {
        if (files.isEmpty()) return null

        val normalizedFormat = format?.lowercase()
        val candidates = filterByFormat(files, normalizedFormat, platform)

        if (candidates.isEmpty()) return null

        // Filter by architecture
        val archFiltered = filterByArch(candidates, arch)
        if (archFiltered.isNotEmpty()) return archFiltered.first()

        // Fallback: no arch-specific match (mono-arch release)
        return candidates.first()
    }

    private fun filterByFormat(
        files: List<YamlFileEntry>,
        format: String?,
        platform: Platform,
    ): List<YamlFileEntry> {
        if (format == null) return filterByPlatform(files, platform)

        // Try suffix-based matching first (for formats sharing .exe)
        val suffix = SUFFIX_FORMATS[format]
        if (suffix != null) {
            val matched = files.filter { it.url.lowercase().contains(suffix) }
            if (matched.isNotEmpty()) return matched
        }

        // Fall back to extension-based matching
        val extension = EXTENSION_FORMATS[format]
        if (extension != null) {
            val matched = files.filter { it.url.lowercase().endsWith(extension) }
            if (matched.isNotEmpty()) return matched
        }

        return emptyList()
    }

    private fun filterByPlatform(
        files: List<YamlFileEntry>,
        platform: Platform,
    ): List<YamlFileEntry> {
        val extensions =
            when (platform) {
                Platform.Windows -> listOf(".exe", ".msi")
                Platform.MacOS -> listOf(".zip", ".dmg")
                Platform.Linux -> listOf(".deb", ".rpm", ".appimage", ".snap")
                Platform.Unknown -> emptyList()
            }
        return files
            .filter { file ->
                val lower = file.url.lowercase()
                extensions.any { lower.endsWith(it) }
            }.sortedBy { file ->
                val lower = file.url.lowercase()
                extensions.indexOfFirst { lower.endsWith(it) }
            }
    }

    private fun filterByArch(
        files: List<YamlFileEntry>,
        arch: Arch,
    ): List<YamlFileEntry> {
        val patterns =
            when (arch) {
                Arch.X64 -> X64_PATTERNS
                Arch.ARM64 -> ARM64_PATTERNS
            }
        return files.filter { file ->
            val lower = file.url.lowercase()
            patterns.any { lower.contains(it) }
        }
    }
}
