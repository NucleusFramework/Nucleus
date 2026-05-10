@file:Suppress("ktlint:standard:filename")

package dev.nucleusframework.nucleus.desktop.application.internal

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File

/**
 * Merges the agent-generated `reachability-metadata.json` into the existing one.
 *
 * Strategy: for each type entry, the **existing** entry is kept as the base and only
 * new information from the agent is added (new types, new methods/fields on existing types).
 * Manual enrichments like `allDeclaredFields: true` are never overwritten by the agent's
 * narrower view.
 */
internal fun mergeReachabilityMetadata(
    agentDir: File,
    targetDir: File,
) {
    val agentFile = File(agentDir, "reachability-metadata.json")
    val targetFile = File(targetDir, "reachability-metadata.json")

    if (!agentFile.exists()) return

    val slurper = JsonSlurper()

    @Suppress("UNCHECKED_CAST")
    val agentRoot = slurper.parseText(agentFile.readText()) as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val targetRoot =
        if (targetFile.exists()) {
            slurper.parseText(targetFile.readText()) as MutableMap<String, Any?>
        } else {
            mutableMapOf()
        }

    // Merge type-based sections (reflection, jni)
    for (sectionName in listOf("reflection", "jni")) {
        @Suppress("UNCHECKED_CAST")
        val agentArray = agentRoot[sectionName] as? List<Map<String, Any?>> ?: continue

        @Suppress("UNCHECKED_CAST")
        val targetArray =
            (targetRoot[sectionName] as? MutableList<MutableMap<String, Any?>>)
                ?: mutableListOf<MutableMap<String, Any?>>().also { targetRoot[sectionName] = it }

        mergeTypeEntries(agentArray, targetArray)
    }

    // For resources/bundles/serialization, just add new entries by JSON equality
    for (sectionName in listOf("resources", "bundles", "serialization")) {
        @Suppress("UNCHECKED_CAST")
        val agentArray = agentRoot[sectionName] as? List<Map<String, Any?>> ?: continue

        @Suppress("UNCHECKED_CAST")
        val targetArray =
            (targetRoot[sectionName] as? MutableList<Map<String, Any?>>)
                ?: mutableListOf<Map<String, Any?>>().also { targetRoot[sectionName] = it }

        mergeSimpleEntries(agentArray, targetArray)
    }

    targetDir.mkdirs()
    targetFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(targetRoot)) + "\n")
}

/**
 * Merges type-based entries (reflection, jni sections).
 * For each agent entry:
 * - If the type doesn't exist in target -> add it
 * - If it exists -> merge methods/fields (add new ones, keep existing)
 * - Never remove or downgrade existing flags (allDeclaredFields, etc.)
 */
private fun mergeTypeEntries(
    agentArray: List<Map<String, Any?>>,
    targetArray: MutableList<MutableMap<String, Any?>>,
) {
    val targetIndex = linkedMapOf<String, MutableMap<String, Any?>>()
    for (entry in targetArray) {
        val typeName = entry["type"] as? String ?: continue
        @Suppress("UNCHECKED_CAST")
        targetIndex[typeName] = entry
    }

    for (agentEntry in agentArray) {
        val typeName = agentEntry["type"] as? String ?: continue
        val existingEntry = targetIndex[typeName]

        if (existingEntry == null) {
            // New type -- add as-is
            val mutableCopy = agentEntry.toMutableMap()
            targetArray.add(mutableCopy)
            targetIndex[typeName] = mutableCopy
        } else {
            // Merge into existing, preserving manual enrichments
            mergeTypeEntry(agentEntry, existingEntry)
        }
    }
}

/**
 * Merges an agent-generated type entry into an existing one.
 * Only adds new methods/fields; never removes or downgrades existing config.
 */
private fun mergeTypeEntry(
    agentEntry: Map<String, Any?>,
    existingEntry: MutableMap<String, Any?>,
) {
    // Preserve broad flags -- only upgrade false->true, never downgrade
    val broadFlags =
        listOf(
            "allDeclaredFields",
            "allDeclaredMethods",
            "allDeclaredConstructors",
            "allPublicFields",
            "allPublicMethods",
            "allPublicConstructors",
            "unsafeAllocated",
            "jniAccessible",
        )
    for (flag in broadFlags) {
        if (agentEntry[flag] == true) {
            existingEntry[flag] = true
        }
        // If existing already has it true, keep it
    }

    // Merge array-based members (methods, fields, queriedMethods)
    for (memberKey in listOf("methods", "fields", "queriedMethods")) {
        @Suppress("UNCHECKED_CAST")
        val agentMembers = agentEntry[memberKey] as? List<Map<String, Any?>> ?: continue

        // If existing has allDeclared* for this category, skip -- already broader
        val allDeclaredKey =
            when (memberKey) {
                "fields" -> "allDeclaredFields"
                "methods", "queriedMethods" -> "allDeclaredMethods"
                else -> null
            }
        if (allDeclaredKey != null && existingEntry[allDeclaredKey] == true) {
            continue
        }

        @Suppress("UNCHECKED_CAST")
        val existingMembers =
            (existingEntry[memberKey] as? MutableList<Map<String, Any?>>)
                ?: mutableListOf<Map<String, Any?>>().also { existingEntry[memberKey] = it }

        mergeMembers(agentMembers, existingMembers)
    }
}

/**
 * Adds new method/field entries from agent that don't already exist in target.
 * Identity is based on "name" + "parameterTypes" (for methods).
 */
private fun mergeMembers(
    agentMembers: List<Map<String, Any?>>,
    existingMembers: MutableList<Map<String, Any?>>,
) {
    val existingSignatures = existingMembers.map { memberSignature(it) }.toMutableSet()

    for (agentMember in agentMembers) {
        val sig = memberSignature(agentMember)
        if (sig !in existingSignatures) {
            existingMembers.add(agentMember)
            existingSignatures.add(sig)
        }
    }
}

/**
 * Produces a comparable signature string for a method/field entry.
 */
private fun memberSignature(obj: Map<String, Any?>): String {
    val name = obj["name"] as? String ?: ""

    @Suppress("UNCHECKED_CAST")
    val params = (obj["parameterTypes"] as? List<String>)?.joinToString(",") ?: ""
    return "$name($params)"
}

/**
 * For simple entries (resources, bundles), adds entries from agent that don't
 * already exist in target. Comparison is by JSON string equality.
 */
private fun mergeSimpleEntries(
    agentArray: List<Map<String, Any?>>,
    targetArray: MutableList<Map<String, Any?>>,
) {
    val existingStrings = targetArray.map { JsonOutput.toJson(it) }.toMutableSet()
    for (entry in agentArray) {
        val str = JsonOutput.toJson(entry)
        if (str !in existingStrings) {
            targetArray.add(entry)
            existingStrings.add(str)
        }
    }
}

/**
 * Removes entries from the project's `reachability-metadata.json` that are already fully
 * covered by library JARs on the classpath, plugin platform metadata, or
 * `native-image.properties` resource inclusion patterns.
 *
 * Sources of "already covered" entries:
 * 1. Library JARs: `META-INF/native-image/ ** /reachability-metadata.json`
 * 2. Plugin L3 platform metadata: `nucleus/graalvm/platform-metadata/{platform}-reachability-metadata.json`
 * 3. `native-image.properties` `-H:IncludeResources=` patterns from library JARs
 * 4. Library resource globs (e.g. `*skiko*.sha256` covers `skiko-windows-x64.dll.sha256`)
 *
 * A reflection/jni entry is removed only when the baseline is a **strict superset** —
 * all methods, fields, and flags in the project entry are present in the baseline.
 */
internal fun deduplicateAgainstLibraryMetadata(
    classpathFiles: Iterable<File>,
    targetDir: File,
    platformName: String? = null,
    mainClass: String? = null,
    extraMetadataDirs: List<File> = emptyList(),
) {
    val targetFile = File(targetDir, "reachability-metadata.json")
    if (!targetFile.exists()) return

    val slurper = JsonSlurper()

    // Collect full library entries per section, keyed by type name.
    // Multiple JARs may contribute entries for the same type -- merge them.
    val libraryEntries = mutableMapOf<String, MutableMap<String, MutableMap<String, Any?>>>()
    val libraryResourceJsons = mutableSetOf<String>()
    val libraryResourceGlobs = mutableListOf<Pair<String?, String>>()
    val includeResourcePatterns = mutableListOf<Regex>()

    for (file in classpathFiles) {
        if (!file.exists() || !file.name.endsWith(".jar")) continue
        try {
            java.util.jar.JarFile(file).use { jar ->
                for (entry in jar.entries()) {
                    // Collect reachability-metadata.json from library JARs
                    if (entry.name.contains("META-INF/native-image/") &&
                        entry.name.endsWith("reachability-metadata.json")
                    ) {
                        val text = jar.getInputStream(entry).bufferedReader().readText()
                        collectLibraryMetadata(slurper, text, libraryEntries, libraryResourceJsons, libraryResourceGlobs)
                    }

                    // Collect IncludeResources patterns from native-image.properties
                    if (entry.name.contains("META-INF/native-image/") &&
                        entry.name.endsWith("native-image.properties")
                    ) {
                        val props = java.util.Properties()
                        jar.getInputStream(entry).use { props.load(it) }
                        val args = props.getProperty("Args") ?: continue
                        val regex = Regex("""-H:IncludeResources=(\S+)""")
                        for (match in regex.findAll(args)) {
                            try {
                                includeResourcePatterns.add(Regex(match.groupValues[1]))
                            } catch (_: Exception) {
                                // Skip malformed patterns
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Skip unreadable JARs
        }
    }

    // Include L3 platform metadata from plugin classpath resources
    if (platformName != null) {
        val resourcePath = "nucleus/graalvm/platform-metadata/$platformName-reachability-metadata.json"
        val stream = object {}::class.java.classLoader.getResourceAsStream(resourcePath)
        if (stream != null) {
            val text = stream.bufferedReader().use { it.readText() }
            collectLibraryMetadata(slurper, text, libraryEntries, libraryResourceJsons, libraryResourceGlobs)
        }
    }

    // Include extra metadata directories (Oracle repo, static analysis, etc.)
    for (dir in extraMetadataDirs) {
        if (!dir.isDirectory) continue
        val metadataFile = File(dir, "reachability-metadata.json")
        if (metadataFile.exists()) {
            try {
                collectLibraryMetadata(slurper, metadataFile.readText(), libraryEntries, libraryResourceJsons, libraryResourceGlobs)
            } catch (_: Exception) {
                // Skip unreadable metadata files
            }
        }
    }

    // Add main class to the baseline so the agent entry gets deduped
    if (!mainClass.isNullOrBlank()) {
        val reflectionMap = libraryEntries.getOrPut("reflection") { mutableMapOf() }
        val mainClassEntry =
            mutableMapOf<String, Any?>(
                "type" to mainClass,
                "jniAccessible" to true,
                "methods" to
                    listOf(
                        mapOf(
                            "name" to "main",
                            "parameterTypes" to listOf("java.lang.String[]"),
                        ),
                    ),
            )
        val existing = reflectionMap[mainClass]
        if (existing == null) {
            reflectionMap[mainClass] = mainClassEntry
        } else {
            mergeTypeEntry(mainClassEntry, existing)
        }
    }

    val hasBaseline =
        libraryEntries.isNotEmpty() ||
            libraryResourceJsons.isNotEmpty() ||
            libraryResourceGlobs.isNotEmpty() ||
            includeResourcePatterns.isNotEmpty()
    if (!hasBaseline) return

    @Suppress("UNCHECKED_CAST")
    val targetRoot = slurper.parseText(targetFile.readText()) as MutableMap<String, Any?>
    var changed = false

    // Remove reflection/jni entries only when the library fully covers the project entry
    for (section in listOf("reflection", "jni")) {
        val sectionMap = libraryEntries[section] ?: continue

        @Suppress("UNCHECKED_CAST")
        val targetArray = targetRoot[section] as? MutableList<Map<String, Any?>> ?: continue
        val before = targetArray.size
        targetArray.removeAll { projectEntry ->
            val typeName = projectEntry["type"] as? String ?: return@removeAll false
            val libEntry = sectionMap[typeName] ?: return@removeAll false
            libraryCoversProject(libEntry, projectEntry)
        }
        if (targetArray.size != before) changed = true
    }

    // Remove resource entries already provided by libraries
    @Suppress("UNCHECKED_CAST")
    val targetResources = targetRoot["resources"] as? MutableList<Map<String, Any?>>
    if (targetResources != null) {
        val before = targetResources.size
        targetResources.removeAll { entry ->
            isResourceCovered(entry, libraryResourceJsons, libraryResourceGlobs, includeResourcePatterns)
        }
        if (targetResources.size != before) changed = true
    }

    if (changed) {
        targetFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(targetRoot)) + "\n")
    }
}

/**
 * Parses a library's reachability-metadata.json and adds its entries to the baseline collections.
 */
private fun collectLibraryMetadata(
    slurper: JsonSlurper,
    jsonText: String,
    libraryEntries: MutableMap<String, MutableMap<String, MutableMap<String, Any?>>>,
    libraryResourceJsons: MutableSet<String>,
    libraryResourceGlobs: MutableList<Pair<String?, String>>,
) {
    @Suppress("UNCHECKED_CAST")
    val libRoot = slurper.parseText(jsonText) as? Map<String, Any?> ?: return

    for (section in listOf("reflection", "jni")) {
        @Suppress("UNCHECKED_CAST")
        val entries = libRoot[section] as? List<Map<String, Any?>> ?: continue
        val sectionMap = libraryEntries.getOrPut(section) { mutableMapOf() }
        for (e in entries) {
            val typeName = e["type"] as? String ?: continue
            val existing = sectionMap[typeName]
            if (existing == null) {
                sectionMap[typeName] = e.toMutableMap()
            } else {
                mergeTypeEntry(e, existing)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    val resources = libRoot["resources"] as? List<Map<String, Any?>> ?: return
    for (e in resources) {
        libraryResourceJsons.add(JsonOutput.toJson(e))
        val glob = e["glob"] as? String
        if (glob != null) {
            val module = e["module"] as? String
            libraryResourceGlobs.add(Pair(module, glob))
        }
    }
}

/**
 * Returns true if a project resource entry is already covered by the library baseline.
 *
 * Checks:
 * 1. Exact JSON match with a library resource entry
 * 2. Agent glob path matches a library resource glob pattern (e.g. `*skiko*.sha256`)
 * 3. Agent glob path matches a `native-image.properties` IncludeResources regex
 */
private fun isResourceCovered(
    entry: Map<String, Any?>,
    libraryResourceJsons: Set<String>,
    libraryResourceGlobs: List<Pair<String?, String>>,
    includeResourcePatterns: List<Regex>,
): Boolean {
    // Exact JSON match (handles bundles and identical glob entries)
    if (JsonOutput.toJson(entry) in libraryResourceJsons) return true

    // Only glob entries can be matched by patterns; bundles need exact match
    val glob = entry["glob"] as? String ?: return false
    val module = entry["module"] as? String

    // Check against library resource globs (e.g. "*skiko*.sha256" covers "skiko-windows-x64.dll.sha256")
    // Module-qualified entries only match against library globs with the same module
    for ((libModule, libGlob) in libraryResourceGlobs) {
        if (module != libModule) continue
        if (libGlob.contains('*') || libGlob.contains('?')) {
            if (globMatches(libGlob, glob)) return true
        }
    }

    // IncludeResources patterns only apply to non-module-qualified entries
    if (module == null) {
        for (pattern in includeResourcePatterns) {
            if (pattern.matches(glob)) return true
        }
    }

    return false
}

/**
 * Tests if a simple glob pattern matches a concrete path.
 * Supports `*` (any chars) and `?` (single char). No `**` or brace expansion.
 */
private fun globMatches(
    pattern: String,
    path: String,
): Boolean {
    val regex =
        buildString {
            append("^")
            for (ch in pattern) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    '.', '(', ')', '[', ']', '{', '}', '\\', '^', '$', '|', '+' -> {
                        append("\\")
                        append(ch)
                    }
                    else -> append(ch)
                }
            }
            append("$")
        }
    return try {
        Regex(regex).matches(path)
    } catch (_: Exception) {
        false
    }
}

/**
 * Returns true if the library entry fully covers the project entry, meaning the project
 * entry can be safely removed. Checks that all methods, fields, and flags in the project
 * entry are present in the library entry.
 */
private fun libraryCoversProject(
    libEntry: Map<String, Any?>,
    projectEntry: Map<String, Any?>,
): Boolean {
    // Check broad flags: if project needs a flag, library must have it
    val broadFlags =
        listOf(
            "allDeclaredFields",
            "allDeclaredMethods",
            "allDeclaredConstructors",
            "allPublicFields",
            "allPublicMethods",
            "allPublicConstructors",
            "unsafeAllocated",
            "jniAccessible",
        )
    for (flag in broadFlags) {
        if (projectEntry[flag] == true && libEntry[flag] != true) {
            return false
        }
    }

    // Check methods, fields, queriedMethods
    for (memberKey in listOf("methods", "fields", "queriedMethods")) {
        @Suppress("UNCHECKED_CAST")
        val projectMembers = projectEntry[memberKey] as? List<Map<String, Any?>>
        if (projectMembers.isNullOrEmpty()) continue

        // If library has allDeclared* for this category, it covers everything
        val allDeclaredKey =
            when (memberKey) {
                "fields" -> "allDeclaredFields"
                "methods", "queriedMethods" -> "allDeclaredMethods"
                else -> null
            }
        if (allDeclaredKey != null && libEntry[allDeclaredKey] == true) continue

        @Suppress("UNCHECKED_CAST")
        val libMembers = libEntry[memberKey] as? List<Map<String, Any?>>
        if (libMembers == null) return false

        val libSignatures = libMembers.map { memberSignature(it) }.toSet()
        for (pm in projectMembers) {
            if (memberSignature(pm) !in libSignatures) {
                return false
            }
        }
    }

    return true
}

/**
 * Writes the platform-specific `reachability-metadata.json` (AWT, Java2D, font entries)
 * bundled inside the plugin JAR into the given [outputDir].
 *
 * If [mainClass] is provided, its reflection entry is injected into the JSON so that
 * users don't need to declare it manually in their project config.
 *
 * The plugin ships pre-built metadata for each platform under
 * `nucleus/graalvm/platform-metadata/{windows,macos,linux}-reachability-metadata.json`.
 */
internal fun writePlatformMetadata(
    platform: String,
    outputDir: File,
    mainClass: String? = null,
) {
    val resourcePath = "nucleus/graalvm/platform-metadata/$platform-reachability-metadata.json"
    val stream =
        object {}::class.java.classLoader.getResourceAsStream(resourcePath)
            ?: return

    outputDir.mkdirs()
    val targetFile = File(outputDir, "reachability-metadata.json")

    if (mainClass.isNullOrBlank()) {
        stream.bufferedReader().use { reader ->
            targetFile.writeText(reader.readText())
        }
    } else {
        val slurper = JsonSlurper()

        @Suppress("UNCHECKED_CAST")
        val root =
            stream.bufferedReader().use {
                slurper.parseText(it.readText()) as MutableMap<String, Any?>
            }

        @Suppress("UNCHECKED_CAST")
        val reflection =
            (root["reflection"] as? MutableList<Any?>)
                ?: mutableListOf<Any?>().also { root["reflection"] = it }

        val mainClassEntry =
            mutableMapOf<String, Any?>(
                "type" to mainClass,
                "jniAccessible" to true,
                "methods" to
                    listOf(
                        mapOf(
                            "name" to "main",
                            "parameterTypes" to listOf("java.lang.String[]"),
                        ),
                    ),
            )
        reflection.add(0, mainClassEntry)

        targetFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(root)) + "\n")
    }
}

/**
 * Merges individual JSON array config files (reflect-config.json, jni-config.json, etc.)
 * that the agent may generate in the old format.
 */
internal fun mergeJsonArrayConfig(
    agentFile: File,
    targetFile: File,
) {
    if (!agentFile.exists()) return

    val slurper = JsonSlurper()

    @Suppress("UNCHECKED_CAST")
    val agentArray = slurper.parseText(agentFile.readText()) as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    val targetArray =
        if (targetFile.exists()) {
            (slurper.parseText(targetFile.readText()) as List<Map<String, Any?>>).toMutableList()
        } else {
            mutableListOf()
        }

    @Suppress("UNCHECKED_CAST")
    val mutableTarget = targetArray as MutableList<MutableMap<String, Any?>>
    mergeTypeEntries(agentArray, mutableTarget)

    targetFile.parentFile.mkdirs()
    targetFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(targetArray)) + "\n")
}
