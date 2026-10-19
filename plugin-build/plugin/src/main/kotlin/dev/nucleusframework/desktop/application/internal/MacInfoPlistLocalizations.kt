package dev.nucleusframework.desktop.application.internal

import java.io.File

private const val LPROJ_SUFFIX = ".lproj"
private const val INFO_PLIST_STRINGS = "InfoPlist.strings"

/** Renders `Info.plist` key → localized value pairs as an `InfoPlist.strings` file (UTF-8). */
internal fun renderInfoPlistStrings(strings: Map<String, String>): String =
    strings.entries.joinToString(separator = "") { (key, value) ->
        "\"${key.escapeStrings()}\" = \"${value.escapeStrings()}\";\n"
    }

/** Renders every language of a `language → (key → value)` map, see [renderInfoPlistStrings]. */
internal fun renderInfoPlistLocalizations(localizations: Map<String, Map<String, String>>): Map<String, String> =
    localizations.mapValues { (_, strings) -> renderInfoPlistStrings(strings) }

/**
 * Installs the localized `Info.plist` values into a bundle's `Contents/Resources` ([resourcesDir]):
 * copies every `<lang>.lproj/` of [localizationsDir], then writes each rendered `InfoPlist.strings`
 * of [renderedStrings] (language → file content). Must run before the bundle is signed.
 */
internal fun installInfoPlistLocalizations(
    resourcesDir: File,
    localizationsDir: File?,
    renderedStrings: Map<String, String>,
) {
    if (localizationsDir != null) {
        check(localizationsDir.isDirectory) {
            "macOS infoPlist localizationsDir does not exist or is not a directory: $localizationsDir"
        }
        localizationsDir
            .listFiles()
            .orEmpty()
            .filterNot { it.name.startsWith(".") }
            .sortedBy { it.name }
            .forEach { entry ->
                check(entry.isDirectory && entry.name.endsWith(LPROJ_SUFFIX)) {
                    "macOS infoPlist localizationsDir must only contain <lang>$LPROJ_SUFFIX directories, found: $entry"
                }
                entry.copyRecursively(resourcesDir.resolve(entry.name), overwrite = true)
            }
    }
    for ((language, content) in renderedStrings) {
        val target = resourcesDir.resolve("$language$LPROJ_SUFFIX/$INFO_PLIST_STRINGS")
        check(localizationsDir?.resolve("$language$LPROJ_SUFFIX/$INFO_PLIST_STRINGS")?.exists() != true) {
            "macOS infoPlist localization(\"$language\") conflicts with $language$LPROJ_SUFFIX/$INFO_PLIST_STRINGS " +
                "in localizationsDir; declare those strings in one place only"
        }
        target.parentFile.mkdirs()
        target.writeText(content)
    }
}

private fun String.escapeStrings(): String =
    buildString(length) {
        for (c in this@escapeStrings) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
