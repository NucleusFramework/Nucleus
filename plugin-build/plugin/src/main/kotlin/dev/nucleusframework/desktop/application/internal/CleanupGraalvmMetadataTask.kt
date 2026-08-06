package dev.nucleusframework.desktop.application.internal

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Cleans up the project's manual `reachability-metadata.json` by removing entries
 * that are already covered by Nucleus-managed metadata sources:
 *
 * - **L1**: Per-library metadata filtered from plugin JAR + library JARs on classpath
 * - **L2**: Oracle GraalVM Reachability Metadata Repository (per-dependency)
 * - **L3**: Platform-specific metadata (AWT/Java2D) shipped in the plugin JAR
 * - **Static analysis**: Reflection/JNI/resource entries detected from bytecode
 * - **native-image.properties**: `-H:IncludeResources=` patterns from library JARs
 *
 * After baseline dedup, a **resolvability** pass scans the runtime classpath for
 * `.class` files and classifies each remaining type entry once:
 * - `removed [baseline]` — covered by L1/L2/L3/static (or main class)
 * - `removed [agent-noise]` — Kotlin mapped-type phantoms (`kotlin.Any`, `kotlin.Int`, …); always
 * - `removed [unresolvable]` — opt-in prune (`-Pnucleus.graalvm.cleanup.removeUnresolvable=true`)
 * - `unresolvable (kept)` — missing type, default report-only
 * - `unresolvable (exact-protected)` — missing type under [exactReachabilityPackages]
 * - `kept` — resolvable but not covered by any managed baseline
 *
 * [exactReachabilityPackages] follows the DSL package list (`APP_PACKAGES` /
 * `packages(...)`), not “was this image built with exact mode”, so cleanup never
 * strips load-bearing negative lookups for apps that use exact mode on the dev loop.
 *
 * `-Pnucleus.graalvm.cleanup.dryRun=true` analyzes without rewriting the config file.
 *
 * Run with: `./gradlew cleanupGraalvmMetadata`
 */
@DisableCachingByDefault(because = "Modifies user source files in-place")
abstract class CleanupGraalvmMetadataTask : DefaultTask() {
    /** Runtime classpath JARs (for L1 library metadata + native-image.properties + class index). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeClasspath: ConfigurableFileCollection

    /** File listing Oracle repo metadata directories (output of resolveGraalvmReachabilityMetadata). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val metadataRepoDirsFile: RegularFileProperty

    /** Static analysis output directory containing reachability-metadata.json. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val staticAnalysisDir: ConfigurableFileCollection

    /** Current platform name (windows, macos, linux) for L3 metadata. */
    @get:Input
    abstract val platformName: Property<String>

    /** Main class name (optional, used to baseline the main entry point). */
    @get:Input
    @get:Optional
    abstract val mainClass: Property<String>

    /** The project's native-image config directory to clean up. */
    @get:Input
    abstract val configDir: Property<File>

    /**
     * When true, drop unresolvable type entries after the baseline pass.
     * Default false (report only) — see [NucleusProperties.GRAALVM_CLEANUP_REMOVE_UNRESOLVABLE].
     */
    @get:Input
    abstract val removeUnresolvable: Property<Boolean>

    /**
     * When true, never rewrite the config file — only log what would change.
     * See [NucleusProperties.GRAALVM_CLEANUP_DRY_RUN].
     */
    @get:Input
    abstract val dryRun: Property<Boolean>

    /**
     * Package prefixes from the `exactReachabilityMetadata` DSL (`APP_PACKAGES` /
     * `packages(...)`). Unresolvable entries under these prefixes are never removed,
     * even with [removeUnresolvable]. Follows the configured scope, not whether a
     * given native-image build actually emitted `--exact-reachability-metadata`.
     */
    @get:Input
    abstract val exactReachabilityPackages: ListProperty<String>

    @TaskAction
    fun cleanup() {
        val targetDir = configDir.get()
        val targetFile = File(targetDir, "reachability-metadata.json")
        if (!targetFile.exists()) {
            logger.lifecycle("No reachability-metadata.json found in $targetDir — nothing to clean up")
            return
        }

        val isDryRun = dryRun.getOrElse(false)
        val shouldRemoveUnresolvable = removeUnresolvable.getOrElse(false)
        val exactPackages = exactReachabilityPackages.getOrElse(emptyList())
        if (isDryRun) {
            logger.lifecycle("Dry run — will not rewrite $targetFile")
        }

        val slurper = JsonSlurper()

        // Collect baseline entries from all managed sources
        val libraryEntries = mutableMapOf<String, MutableMap<String, MutableMap<String, Any?>>>()
        val libraryProxies = mutableSetOf<String>()
        val libraryResourceJsons = mutableSetOf<String>()
        val libraryResourceGlobs = mutableListOf<Pair<String?, String>>()
        val includeResourcePatterns = mutableListOf<Regex>()

        // Source 1: L1 — library JARs on classpath (META-INF/native-image/**/reachability-metadata.json)
        // + native-image.properties IncludeResources patterns
        var l1Count = 0
        for (file in runtimeClasspath.files) {
            if (!file.exists() || !file.name.endsWith(".jar")) continue
            try {
                java.util.jar.JarFile(file).use { jar ->
                    for (entry in jar.entries()) {
                        if (entry.name.contains("META-INF/native-image/") &&
                            entry.name.endsWith("reachability-metadata.json")
                        ) {
                            val text = jar.getInputStream(entry).bufferedReader().readText()
                            val before = countBaselineTypes(libraryEntries)
                            collectBaseline(slurper, text, libraryEntries, libraryProxies, libraryResourceJsons, libraryResourceGlobs)
                            l1Count += countBaselineTypes(libraryEntries) - before
                        }

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

        // Source 2: L2 — Oracle repo metadata directories
        var l2Count = 0
        val repoDirsFile = metadataRepoDirsFile.orNull?.asFile
        if (repoDirsFile != null && repoDirsFile.exists()) {
            val dirs = repoDirsFile.readText().trim()
            for (dirPath in dirs.lines()) {
                if (dirPath.isBlank()) continue
                val dir = File(dirPath)
                if (!dir.isDirectory) continue
                // New format
                val newFormatFile = File(dir, "reachability-metadata.json")
                if (newFormatFile.exists()) {
                    val before = countBaselineTypes(libraryEntries)
                    collectBaseline(
                        slurper,
                        newFormatFile.readText(),
                        libraryEntries,
                        libraryProxies,
                        libraryResourceJsons,
                        libraryResourceGlobs,
                    )
                    l2Count += countBaselineTypes(libraryEntries) - before
                }
                // Old format
                for (oldFile in listOf("reflect-config.json", "jni-config.json")) {
                    val f = File(dir, oldFile)
                    if (!f.exists()) continue
                    val section = if (oldFile.startsWith("reflect")) "reflection" else "jni"

                    @Suppress("UNCHECKED_CAST")
                    val entries = slurper.parseText(f.readText()) as? List<Map<String, Any?>> ?: continue
                    val sectionMap = libraryEntries.getOrPut(section) { mutableMapOf() }
                    for (e in entries) {
                        val typeName = e["type"] as? String ?: continue
                        val existing = sectionMap[typeName]
                        if (existing == null) {
                            sectionMap[typeName] = e.toMutableMap()
                            l2Count++
                        } else {
                            mergeTypeEntryInto(e, existing)
                        }
                    }
                }
                // Old-format resource-config.json
                val resFile = File(dir, "resource-config.json")
                if (resFile.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val resRoot = slurper.parseText(resFile.readText()) as? Map<String, Any?>

                    @Suppress("UNCHECKED_CAST")
                    val resources = resRoot?.get("resources") as? Map<String, Any?>

                    @Suppress("UNCHECKED_CAST")
                    val includes = resources?.get("includes") as? List<Map<String, Any?>>
                    includes?.forEach { inc ->
                        val pattern = inc["pattern"] as? String
                        if (pattern != null) {
                            libraryResourceGlobs.add(Pair(null, pattern))
                            libraryResourceJsons.add(JsonOutput.toJson(mapOf("glob" to pattern)))
                        }
                    }
                }
            }
        }

        // Source 3: L3 — platform-specific metadata from plugin JAR
        var l3Count = 0
        val platform = platformName.get()
        val resourcePath = "nucleus/graalvm/platform-metadata/$platform-reachability-metadata.json"
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
        if (stream != null) {
            val text = stream.bufferedReader().use { it.readText() }
            val before = countBaselineTypes(libraryEntries)
            collectBaseline(slurper, text, libraryEntries, libraryProxies, libraryResourceJsons, libraryResourceGlobs)
            l3Count = countBaselineTypes(libraryEntries) - before
        }

        // Source 4: Static analysis output
        var staticCount = 0
        for (dir in staticAnalysisDir.files) {
            val staticFile = if (dir.isDirectory) File(dir, "reachability-metadata.json") else dir
            if (staticFile.exists() && staticFile.name.endsWith(".json")) {
                val before = countBaselineTypes(libraryEntries)
                collectBaseline(
                    slurper,
                    staticFile.readText(),
                    libraryEntries,
                    libraryProxies,
                    libraryResourceJsons,
                    libraryResourceGlobs,
                )
                staticCount = countBaselineTypes(libraryEntries) - before
            }
        }

        // Add main class to baseline
        val mc = mainClass.orNull
        if (!mc.isNullOrBlank()) {
            val reflectionMap = libraryEntries.getOrPut("reflection") { mutableMapOf() }
            val mainClassEntry =
                mutableMapOf<String, Any?>(
                    "type" to mc,
                    "jniAccessible" to true,
                    "methods" to
                        listOf(
                            mapOf("name" to "main", "parameterTypes" to listOf("java.lang.String[]")),
                        ),
                )
            val existing = reflectionMap[mc]
            if (existing == null) {
                reflectionMap[mc] = mainClassEntry
            } else {
                mergeTypeEntryInto(mainClassEntry, existing)
            }
        }

        logger.lifecycle(
            "Metadata baseline: L1=$l1Count types, L2=$l2Count types, " +
                "L3=$l3Count types, static=$staticCount types, " +
                "${includeResourcePatterns.size} resource patterns",
        )

        // Parse the project's manual config
        @Suppress("UNCHECKED_CAST")
        val targetRoot = slurper.parseText(targetFile.readText()) as MutableMap<String, Any?>

        // Single disposition table for the whole run (baseline + resolvability + leftovers).
        val report = mutableListOf<String>()
        var baselineRemoved = 0
        var unresolvableRemoved = 0
        var unresolvableReported = 0
        var unresolvableProtected = 0

        // Clean reflection/jni sections against same-section AND cross-section baselines
        // (static analyzer puts SQLite in "jni", manual config may have it in "reflection")
        for (projectSection in listOf("reflection", "jni")) {
            @Suppress("UNCHECKED_CAST")
            val targetArray = targetRoot[projectSection] as? MutableList<Map<String, Any?>> ?: continue
            val before = targetArray.size

            targetArray.removeAll { projectEntry ->
                // Handle proxy entries: {"type": {"proxy": [...]}}
                val pk = proxyKey(projectEntry)
                if (pk != null) {
                    if (pk in libraryProxies) {
                        report.add("  [removed/baseline] [$projectSection proxy] $pk")
                        return@removeAll true
                    }
                    return@removeAll false
                }

                val typeName = projectEntry["type"] as? String ?: return@removeAll false

                // Check same section first, then cross-section
                for (baselineSection in listOf("reflection", "jni")) {
                    val sectionMap = libraryEntries[baselineSection] ?: continue
                    val libEntry = sectionMap[typeName] ?: continue
                    if (libraryCoversProjectEntry(libEntry, projectEntry)) {
                        val source =
                            if (baselineSection == projectSection) {
                                projectSection
                            } else {
                                "$projectSection via $baselineSection"
                            }
                        report.add("  [removed/baseline] [$source] $typeName")
                        return@removeAll true
                    }
                }

                // For Kotlin data objects: tracing agent emits Foo$Companion with serializer(),
                // but the actual class is Foo (no Companion class exists). If the parent class
                // is in the baseline with serializer(), consider the Companion entry covered.
                if (typeName.endsWith("\$Companion")) {
                    val parentType = typeName.removeSuffix("\$Companion")
                    for (baselineSection in listOf("reflection", "jni")) {
                        val sectionMap = libraryEntries[baselineSection] ?: continue
                        val parentEntry = sectionMap[parentType] ?: continue

                        @Suppress("UNCHECKED_CAST")
                        val parentMethods = parentEntry["methods"] as? List<Map<String, Any?>>
                        val hasSerializer = parentMethods?.any { it["name"] == "serializer" } == true
                        if (hasSerializer) {
                            report.add("  [removed/baseline] [$projectSection via parent] $typeName")
                            return@removeAll true
                        }
                    }
                }

                false
            }
            baselineRemoved += before - targetArray.size
        }

        // Clean resources section
        @Suppress("UNCHECKED_CAST")
        val targetResources = targetRoot["resources"] as? MutableList<Map<String, Any?>>
        if (targetResources != null) {
            val before = targetResources.size
            targetResources.removeAll { entry ->
                val covered =
                    isResourceEntryCovered(
                        entry,
                        libraryResourceJsons,
                        libraryResourceGlobs,
                        includeResourcePatterns,
                    )
                if (covered) {
                    val glob = entry["glob"] ?: entry["bundle"] ?: "?"
                    report.add("  [removed/baseline] [resource] $glob")
                }
                covered
            }
            baselineRemoved += before - targetResources.size
        }

        // ── Resolvability pass ──
        // Agent-recorded negative Class.forName lookups (kotlin.Any, kotlin.Int, …) and
        // typos/stale renames never match a baseline. Classify each survivor once.
        // Kotlin mapped-type phantoms are always removed (AGENT_NOISE); other missing
        // types stay report-only unless removeUnresolvable is on.
        val classIndex = buildClasspathClassIndex(runtimeClasspath.files)
        logger.lifecycle("Classpath class index: ${classIndex.size} types")

        // Track which survivors were already classified as unresolvable so the final
        // "kept" pass does not re-list them.
        val classifiedUnresolvableKeys = mutableSetOf<String>()
        var agentNoiseRemoved = 0
        for (section in listOf("reflection", "jni", "serialization")) {
            @Suppress("UNCHECKED_CAST")
            val targetArray = targetRoot[section] as? MutableList<Map<String, Any?>> ?: continue
            val before = targetArray.size
            var sectionAgentNoise = 0
            targetArray.removeAll { projectEntry ->
                when (
                    classifyUnresolvableEntry(
                        projectEntry,
                        classIndex,
                        exactPackages,
                        shouldRemoveUnresolvable,
                    )
                ) {
                    UnresolvableDisposition.RESOLVABLE -> false
                    UnresolvableDisposition.AGENT_NOISE -> {
                        report.add(formatDispositionLine("removed/agent-noise", section, projectEntry))
                        sectionAgentNoise++
                        true
                    }
                    UnresolvableDisposition.REMOVE -> {
                        report.add(formatDispositionLine("removed/unresolvable", section, projectEntry))
                        true
                    }
                    UnresolvableDisposition.REPORT -> {
                        report.add(formatDispositionLine("unresolvable/kept", section, projectEntry))
                        classifiedUnresolvableKeys.add("$section|${entryDisplayName(projectEntry)}")
                        unresolvableReported++
                        false
                    }
                    UnresolvableDisposition.PROTECT -> {
                        report.add(formatDispositionLine("unresolvable/exact-protected", section, projectEntry))
                        classifiedUnresolvableKeys.add("$section|${entryDisplayName(projectEntry)}")
                        unresolvableProtected++
                        false
                    }
                }
            }
            val removedHere = before - targetArray.size
            agentNoiseRemoved += sectionAgentNoise
            unresolvableRemoved += removedHere - sectionAgentNoise
        }

        // Resolvable leftovers not covered by any managed baseline — worth a human look.
        // Member-less {"type": X} is not provably useless (Class.forName(X) succeeds).
        var keptResolvable = 0
        for (section in listOf("reflection", "jni", "serialization")) {
            @Suppress("UNCHECKED_CAST")
            val arr = targetRoot[section] as? List<Map<String, Any?>> ?: continue
            for (entry in arr) {
                val key = "$section|${entryDisplayName(entry)}"
                if (key in classifiedUnresolvableKeys) continue
                report.add(formatDispositionLine("kept", section, entry))
                keptResolvable++
            }
        }
        @Suppress("UNCHECKED_CAST")
        val resArr = targetRoot["resources"] as? List<Map<String, Any?>>
        resArr?.forEach { entry ->
            val glob = entry["glob"] ?: entry["bundle"] ?: "?"
            report.add("  [kept] [resource] $glob")
            keptResolvable++
        }

        val totalRemoved = baselineRemoved + unresolvableRemoved + agentNoiseRemoved
        if (totalRemoved > 0) {
            for (section in listOf("reflection", "jni", "resources", "bundles", "serialization")) {
                @Suppress("UNCHECKED_CAST")
                val arr = targetRoot[section] as? List<*>
                if (arr != null && arr.isEmpty()) {
                    targetRoot.remove(section)
                }
            }
            if (!isDryRun) {
                targetFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(targetRoot)) + "\n")
            }
        }

        val verb = if (isDryRun) "Would remove" else "Removed"
        when {
            totalRemoved > 0 ->
                logger.lifecycle(
                    "$verb $totalRemoved entr${if (totalRemoved == 1) "y" else "ies"} " +
                        "(baseline=$baselineRemoved, unresolvable=$unresolvableRemoved, " +
                        "agent-noise=$agentNoiseRemoved) from $targetFile",
                )
            unresolvableReported > 0 || unresolvableProtected > 0 ->
                logger.lifecycle(
                    "No baseline-redundant entries — " +
                        "$unresolvableReported unresolvable kept" +
                        (if (unresolvableProtected > 0) ", $unresolvableProtected exact-protected" else "") +
                        (if (!shouldRemoveUnresolvable && unresolvableReported > 0) {
                            " (pass -P${NucleusProperties.GRAALVM_CLEANUP_REMOVE_UNRESOLVABLE}=true to remove)"
                        } else {
                            ""
                        }),
                )
            else ->
                logger.lifecycle("No redundant or unresolvable entries — manual config is already clean")
        }

        if (report.isNotEmpty()) {
            logger.lifecycle("Disposition (${report.size}):")
            report.forEach { logger.lifecycle(it) }
        }
        logger.lifecycle(
            "Summary: removed=$totalRemoved " +
                "(baseline=$baselineRemoved, unresolvable=$unresolvableRemoved, " +
                "agent-noise=$agentNoiseRemoved), " +
                "unresolvable/kept=$unresolvableReported, " +
                "unresolvable/exact-protected=$unresolvableProtected, " +
                "kept=$keptResolvable",
        )
    }

    private fun countBaselineTypes(entries: Map<String, Map<String, MutableMap<String, Any?>>>): Int =
        entries.values.sumOf { it.size }

    private fun collectBaseline(
        slurper: JsonSlurper,
        jsonText: String,
        libraryEntries: MutableMap<String, MutableMap<String, MutableMap<String, Any?>>>,
        libraryProxies: MutableSet<String>,
        libraryResourceJsons: MutableSet<String>,
        libraryResourceGlobs: MutableList<Pair<String?, String>>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val root = slurper.parseText(jsonText) as? Map<String, Any?> ?: return

        for (section in listOf("reflection", "jni")) {
            @Suppress("UNCHECKED_CAST")
            val entries = root[section] as? List<Map<String, Any?>> ?: continue
            val sectionMap = libraryEntries.getOrPut(section) { mutableMapOf() }
            for (e in entries) {
                // Handle proxy entries
                val pk = proxyKey(e)
                if (pk != null) {
                    libraryProxies.add(pk)
                    continue
                }
                val typeName = e["type"] as? String ?: continue
                val existing = sectionMap[typeName]
                if (existing == null) {
                    sectionMap[typeName] = e.toMutableMap()
                } else {
                    mergeTypeEntryInto(e, existing)
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        val resources = root["resources"] as? List<Map<String, Any?>> ?: return
        for (e in resources) {
            libraryResourceJsons.add(JsonOutput.toJson(e))
            val glob = e["glob"] as? String
            if (glob != null) {
                val module = e["module"] as? String
                libraryResourceGlobs.add(Pair(module, glob))
            }
        }
    }

    /** Merge source entry into target, only upgrading (never downgrading). */
    private fun mergeTypeEntryInto(
        source: Map<String, Any?>,
        target: MutableMap<String, Any?>,
    ) {
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
            if (source[flag] == true) target[flag] = true
        }

        for (memberKey in listOf("methods", "fields", "queriedMethods")) {
            @Suppress("UNCHECKED_CAST")
            val sourceMembers = source[memberKey] as? List<Map<String, Any?>> ?: continue

            @Suppress("UNCHECKED_CAST")
            val targetMembers =
                (target[memberKey] as? MutableList<Map<String, Any?>>)
                    ?: mutableListOf<Map<String, Any?>>().also { target[memberKey] = it }

            val existingSigs = targetMembers.map { sig(it) }.toMutableSet()
            for (m in sourceMembers) {
                val s = sig(m)
                if (s !in existingSigs) {
                    targetMembers.add(m)
                    existingSigs.add(s)
                }
            }
        }
    }

    private fun sig(obj: Map<String, Any?>): String {
        val name = obj["name"] as? String ?: ""

        @Suppress("UNCHECKED_CAST")
        val params = (obj["parameterTypes"] as? List<String>)?.joinToString(",") ?: ""
        return "$name($params)"
    }

    /**
     * True if the baseline entry fully covers the project entry.
     * When checking cross-section (jni baseline vs reflection project), the jniAccessible
     * flag mismatch is ignored since having the type in jni config is sufficient.
     */
    private fun libraryCoversProjectEntry(
        libEntry: Map<String, Any?>,
        projectEntry: Map<String, Any?>,
    ): Boolean {
        // Check broad flags: if project needs a flag, library must have it
        // Exception: jniAccessible — if the type exists in the jni section baseline,
        // the flag is implicitly satisfied
        val broadFlags =
            listOf(
                "allDeclaredFields",
                "allDeclaredMethods",
                "allDeclaredConstructors",
                "allPublicFields",
                "allPublicMethods",
                "allPublicConstructors",
                "unsafeAllocated",
            )
        for (flag in broadFlags) {
            if (projectEntry[flag] == true && libEntry[flag] != true) return false
        }

        for (memberKey in listOf("methods", "fields", "queriedMethods")) {
            @Suppress("UNCHECKED_CAST")
            val projectMembers = projectEntry[memberKey] as? List<Map<String, Any?>>
            if (projectMembers.isNullOrEmpty()) continue

            val allDeclaredKey =
                when (memberKey) {
                    "fields" -> "allDeclaredFields"
                    "methods", "queriedMethods" -> "allDeclaredMethods"
                    else -> null
                }
            if (allDeclaredKey != null && libEntry[allDeclaredKey] == true) continue

            @Suppress("UNCHECKED_CAST")
            val libMembers = libEntry[memberKey] as? List<Map<String, Any?>> ?: return false

            val libSigs = libMembers.map { sig(it) }.toSet()
            for (pm in projectMembers) {
                if (sig(pm) !in libSigs) return false
            }
        }

        return true
    }

    /** True if a resource entry is covered by the baseline. */
    private fun isResourceEntryCovered(
        entry: Map<String, Any?>,
        libraryResourceJsons: Set<String>,
        libraryResourceGlobs: List<Pair<String?, String>>,
        includeResourcePatterns: List<Regex>,
    ): Boolean {
        if (JsonOutput.toJson(entry) in libraryResourceJsons) return true

        val glob = entry["glob"] as? String ?: return false
        val module = entry["module"] as? String

        for ((libModule, libGlob) in libraryResourceGlobs) {
            if (module != libModule) continue
            if (libGlob.contains('*') || libGlob.contains('?')) {
                if (globMatches(libGlob, glob)) return true
            }
            // Exact match
            if (libGlob == glob) return true
        }

        if (module == null) {
            for (pattern in includeResourcePatterns) {
                if (pattern.matches(glob)) return true
            }
        }

        return false
    }

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
}
