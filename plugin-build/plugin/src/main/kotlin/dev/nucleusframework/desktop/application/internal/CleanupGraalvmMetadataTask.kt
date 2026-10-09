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

    /**
     * Removes the entries of the project's `reachability-metadata.json` that the managed
     * baselines already cover, classifies the survivors by resolvability, and logs the
     * disposition of every entry. Leaves the file untouched under [dryRun].
     */
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
        val baseline = collectBaseline(slurper)

        // Parse the project's manual config
        @Suppress("UNCHECKED_CAST")
        val targetRoot = slurper.parseText(targetFile.readText()) as MutableMap<String, Any?>

        // Single disposition table for the whole run (baseline + resolvability + leftovers).
        val report = CleanupReport()
        removeBaselineCovered(targetRoot, baseline, report)

        // ── Resolvability pass ──
        // Agent-recorded negative Class.forName lookups (kotlin.Any, kotlin.Int, …) and
        // typos/stale renames never match a baseline. Classify each survivor once.
        // Kotlin mapped-type phantoms are always removed (AGENT_NOISE); other missing
        // types stay report-only unless removeUnresolvable is on.
        val classIndex = buildClasspathClassIndex(runtimeClasspath.files)
        logger.lifecycle("Classpath class index: ${classIndex.size} types")
        for (section in TYPE_SECTIONS_WITH_SERIALIZATION) {
            @Suppress("UNCHECKED_CAST")
            val targetArray = targetRoot[section] as? MutableList<Map<String, Any?>> ?: continue
            targetArray.removeAll { projectEntry ->
                val disposition =
                    classifyUnresolvableEntry(
                        projectEntry,
                        classIndex,
                        exactPackages,
                        shouldRemoveUnresolvable,
                    )
                report.recordDisposition(disposition, section, projectEntry)
            }
        }

        report.recordKept(targetRoot)

        if (report.totalRemoved > 0) {
            for (section in listOf("reflection", "jni", "resources", "bundles", "serialization")) {
                val arr = targetRoot[section] as? List<*>
                if (arr != null && arr.isEmpty()) {
                    targetRoot.remove(section)
                }
            }
            if (!isDryRun) {
                targetFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(targetRoot)) + "\n")
            }
        }

        logOutcome(report, isDryRun, shouldRemoveUnresolvable, targetFile)
    }

    /** Collects the entries every managed metadata source already provides. */
    private fun collectBaseline(slurper: JsonSlurper): CleanupBaseline {
        val baseline = CleanupBaseline()

        // Source 1: L1 — library JARs on classpath (META-INF/native-image/**/reachability-metadata.json)
        // + native-image.properties IncludeResources patterns
        var l1Count = 0
        for (file in runtimeClasspath.files) {
            if (file.exists() && file.name.endsWith(".jar")) {
                l1Count += baseline.collectFromJar(slurper, file)
            }
        }

        // Source 2: L2 — Oracle repo metadata directories
        var l2Count = 0
        val repoDirsFile = metadataRepoDirsFile.orNull?.asFile
        if (repoDirsFile != null && repoDirsFile.exists()) {
            val dirs = repoDirsFile.readText().trim()
            for (dirPath in dirs.lines()) {
                if (dirPath.isNotBlank() && File(dirPath).isDirectory) {
                    l2Count += baseline.collectRepositoryDir(slurper, File(dirPath))
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
            l3Count = baseline.collect(slurper, text)
        }

        // Source 4: Static analysis output
        var staticCount = 0
        for (dir in staticAnalysisDir.files) {
            val staticFile = if (dir.isDirectory) File(dir, "reachability-metadata.json") else dir
            if (staticFile.exists() && staticFile.name.endsWith(".json")) {
                staticCount = baseline.collect(slurper, staticFile.readText())
            }
        }

        // Add main class to baseline
        val mc = mainClass.orNull
        if (!mc.isNullOrBlank()) {
            baseline.addMainClass(mc)
        }

        logger.lifecycle(
            "Metadata baseline: L1=$l1Count types, L2=$l2Count types, " +
                "L3=$l3Count types, static=$staticCount types, " +
                "${baseline.includeResourcePatternCount} resource patterns",
        )
        return baseline
    }

    /**
     * Cleans reflection/jni sections against same-section AND cross-section baselines
     * (static analyzer puts SQLite in "jni", manual config may have it in "reflection"),
     * then the resources section.
     */
    private fun removeBaselineCovered(
        targetRoot: MutableMap<String, Any?>,
        baseline: CleanupBaseline,
        report: CleanupReport,
    ) {
        for (projectSection in TYPE_SECTIONS) {
            @Suppress("UNCHECKED_CAST")
            val targetArray = targetRoot[projectSection] as? MutableList<Map<String, Any?>> ?: continue
            val before = targetArray.size
            targetArray.removeAll { projectEntry ->
                val label = baseline.coverageLabel(projectSection, projectEntry)
                if (label != null) report.lines.add("  [removed/baseline] $label")
                label != null
            }
            report.baselineRemoved += before - targetArray.size
        }

        @Suppress("UNCHECKED_CAST")
        val targetResources = targetRoot["resources"] as? MutableList<Map<String, Any?>>
        if (targetResources != null) {
            val before = targetResources.size
            targetResources.removeAll { entry ->
                val covered = baseline.isResourceEntryCovered(entry)
                if (covered) {
                    val glob = entry["glob"] ?: entry["bundle"] ?: "?"
                    report.lines.add("  [removed/baseline] [resource] $glob")
                }
                covered
            }
            report.baselineRemoved += before - targetResources.size
        }
    }

    private fun logOutcome(
        report: CleanupReport,
        isDryRun: Boolean,
        shouldRemoveUnresolvable: Boolean,
        targetFile: File,
    ) {
        val totalRemoved = report.totalRemoved
        val protectedSuffix =
            if (report.unresolvableProtected > 0) ", ${report.unresolvableProtected} exact-protected" else ""
        val verb = if (isDryRun) "Would remove" else "Removed"
        when {
            totalRemoved > 0 ->
                logger.lifecycle(
                    "$verb $totalRemoved entr${if (totalRemoved == 1) "y" else "ies"} " +
                        "(baseline=${report.baselineRemoved}, unresolvable=${report.unresolvableRemoved}, " +
                        "agent-noise=${report.agentNoiseRemoved}) from $targetFile",
                )
            report.unresolvableReported > 0 || report.unresolvableProtected > 0 ->
                logger.lifecycle(
                    "No baseline-redundant entries — " +
                        "${report.unresolvableReported} unresolvable kept" +
                        protectedSuffix +
                        (if (!shouldRemoveUnresolvable && report.unresolvableReported > 0) {
                            " (pass -P${NucleusProperties.GRAALVM_CLEANUP_REMOVE_UNRESOLVABLE}=true to remove)"
                        } else {
                            ""
                        }),
                )
            else ->
                logger.lifecycle("No redundant or unresolvable entries — manual config is already clean")
        }

        if (report.lines.isNotEmpty()) {
            logger.lifecycle("Disposition (${report.lines.size}):")
            report.lines.forEach { logger.lifecycle(it) }
        }
        logger.lifecycle(
            "Summary: removed=$totalRemoved " +
                "(baseline=${report.baselineRemoved}, unresolvable=${report.unresolvableRemoved}, " +
                "agent-noise=${report.agentNoiseRemoved}), " +
                "unresolvable/kept=${report.unresolvableReported}, " +
                "unresolvable/exact-protected=${report.unresolvableProtected}, " +
                "kept=${report.keptResolvable}",
        )
    }
}

private val TYPE_SECTIONS = listOf("reflection", "jni")

private val TYPE_SECTIONS_WITH_SERIALIZATION = listOf("reflection", "jni", "serialization")

/** Disposition lines and counters of one [CleanupGraalvmMetadataTask] run. */
private class CleanupReport {
    val lines = mutableListOf<String>()

    /**
     * Survivors already classified as unresolvable, so the final "kept" pass does not
     * re-list them.
     */
    private val classifiedUnresolvableKeys = mutableSetOf<String>()

    var baselineRemoved = 0
    var unresolvableRemoved = 0
    var unresolvableReported = 0
    var unresolvableProtected = 0
    var agentNoiseRemoved = 0
    var keptResolvable = 0

    val totalRemoved: Int
        get() = baselineRemoved + unresolvableRemoved + agentNoiseRemoved

    /** Records a resolvability disposition; returns true when the entry must be removed. */
    fun recordDisposition(
        disposition: UnresolvableDisposition,
        section: String,
        projectEntry: Map<String, Any?>,
    ): Boolean =
        when (disposition) {
            UnresolvableDisposition.RESOLVABLE -> false
            UnresolvableDisposition.AGENT_NOISE -> {
                lines.add(formatDispositionLine("removed/agent-noise", section, projectEntry))
                agentNoiseRemoved++
                true
            }
            UnresolvableDisposition.REMOVE -> {
                lines.add(formatDispositionLine("removed/unresolvable", section, projectEntry))
                unresolvableRemoved++
                true
            }
            UnresolvableDisposition.REPORT -> {
                lines.add(formatDispositionLine("unresolvable/kept", section, projectEntry))
                classifiedUnresolvableKeys.add("$section|${entryDisplayName(projectEntry)}")
                unresolvableReported++
                false
            }
            UnresolvableDisposition.PROTECT -> {
                lines.add(formatDispositionLine("unresolvable/exact-protected", section, projectEntry))
                classifiedUnresolvableKeys.add("$section|${entryDisplayName(projectEntry)}")
                unresolvableProtected++
                false
            }
        }

    /**
     * Lists resolvable leftovers not covered by any managed baseline — worth a human look.
     * Member-less {"type": X} is not provably useless (Class.forName(X) succeeds).
     */
    fun recordKept(targetRoot: Map<String, Any?>) {
        for (section in TYPE_SECTIONS_WITH_SERIALIZATION) {
            @Suppress("UNCHECKED_CAST")
            val arr = targetRoot[section] as? List<Map<String, Any?>> ?: continue
            arr
                .filter { "$section|${entryDisplayName(it)}" !in classifiedUnresolvableKeys }
                .forEach { entry ->
                    lines.add(formatDispositionLine("kept", section, entry))
                    keptResolvable++
                }
        }
        @Suppress("UNCHECKED_CAST")
        val resArr = targetRoot["resources"] as? List<Map<String, Any?>>
        resArr?.forEach { entry ->
            val glob = entry["glob"] ?: entry["bundle"] ?: "?"
            lines.add("  [kept] [resource] $glob")
            keptResolvable++
        }
    }
}

/** Entries that the managed metadata sources (L1/L2/L3/static, main class) already provide. */
private class CleanupBaseline {
    private val entries = mutableMapOf<String, MutableMap<String, MutableMap<String, Any?>>>()
    private val proxies = mutableSetOf<String>()
    private val resourceJsons = mutableSetOf<String>()
    private val resourceGlobs = mutableListOf<Pair<String?, String>>()
    private val includeResourcePatterns = mutableListOf<Regex>()

    val includeResourcePatternCount: Int
        get() = includeResourcePatterns.size

    private val typeCount: Int
        get() = entries.values.sumOf { it.size }

    /**
     * Collects `reachability-metadata.json` and `native-image.properties` under
     * `META-INF/native-image/` of a library JAR. Returns the number of types added;
     * unreadable JARs are skipped.
     */
    fun collectFromJar(
        slurper: JsonSlurper,
        file: File,
    ): Int {
        var added = 0
        try {
            java.util.jar.JarFile(file).use { jar ->
                for (entry in jar.entries()) {
                    if (entry.name.contains("META-INF/native-image/")) {
                        added += collectFromJarEntry(slurper, jar, entry)
                    }
                }
            }
        } catch (_: Exception) {
            // Skip unreadable JARs
        }
        return added
    }

    private fun collectFromJarEntry(
        slurper: JsonSlurper,
        jar: java.util.jar.JarFile,
        entry: java.util.jar.JarEntry,
    ): Int {
        if (entry.name.endsWith("reachability-metadata.json")) {
            return collect(slurper, jar.getInputStream(entry).bufferedReader().readText())
        }
        if (entry.name.endsWith("native-image.properties")) {
            val props = java.util.Properties()
            jar.getInputStream(entry).use { props.load(it) }
            val args = props.getProperty("Args") ?: return 0
            val regex = Regex("""-H:IncludeResources=(\S+)""")
            for (match in regex.findAll(args)) {
                try {
                    includeResourcePatterns.add(Regex(match.groupValues[1]))
                } catch (_: Exception) {
                    // Skip malformed patterns
                }
            }
        }
        return 0
    }

    /**
     * Collects an Oracle repository metadata directory, in the new single-file format
     * and the legacy `reflect/jni/resource-config.json` one. Returns the number of types added.
     */
    fun collectRepositoryDir(
        slurper: JsonSlurper,
        dir: File,
    ): Int {
        var added = 0
        // New format
        val newFormatFile = File(dir, "reachability-metadata.json")
        if (newFormatFile.exists()) {
            added += collect(slurper, newFormatFile.readText())
        }
        // Old format
        for (oldFile in listOf("reflect-config.json", "jni-config.json")) {
            val f = File(dir, oldFile)
            if (f.exists()) {
                val section = if (oldFile.startsWith("reflect")) "reflection" else "jni"
                added += collectLegacyTypeConfig(slurper, f, section)
            }
        }
        // Old-format resource-config.json
        val resFile = File(dir, "resource-config.json")
        if (resFile.exists()) {
            collectLegacyResourceConfig(slurper, resFile)
        }
        return added
    }

    private fun collectLegacyTypeConfig(
        slurper: JsonSlurper,
        file: File,
        section: String,
    ): Int {
        @Suppress("UNCHECKED_CAST")
        val sectionEntries = slurper.parseText(file.readText()) as? List<Map<String, Any?>> ?: return 0
        val sectionMap = entries.getOrPut(section) { mutableMapOf() }
        var added = 0
        for (e in sectionEntries) {
            val typeName = e["type"] as? String ?: continue
            val existing = sectionMap[typeName]
            if (existing == null) {
                sectionMap[typeName] = e.toMutableMap()
                added++
            } else {
                mergeTypeEntryInto(e, existing)
            }
        }
        return added
    }

    private fun collectLegacyResourceConfig(
        slurper: JsonSlurper,
        file: File,
    ) {
        @Suppress("UNCHECKED_CAST")
        val resRoot = slurper.parseText(file.readText()) as? Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val resources = resRoot?.get("resources") as? Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val includes = resources?.get("includes") as? List<Map<String, Any?>>
        includes?.forEach { inc ->
            val pattern = inc["pattern"] as? String
            if (pattern != null) {
                resourceGlobs.add(Pair(null, pattern))
                resourceJsons.add(JsonOutput.toJson(mapOf("glob" to pattern)))
            }
        }
    }

    /** Collects a `reachability-metadata.json` document. Returns the number of types added. */
    fun collect(
        slurper: JsonSlurper,
        jsonText: String,
    ): Int {
        val before = typeCount
        collectInto(slurper, jsonText)
        return typeCount - before
    }

    private fun collectInto(
        slurper: JsonSlurper,
        jsonText: String,
    ) {
        @Suppress("UNCHECKED_CAST")
        val root = slurper.parseText(jsonText) as? Map<String, Any?> ?: return

        for (section in TYPE_SECTIONS) {
            @Suppress("UNCHECKED_CAST")
            val sectionEntries = root[section] as? List<Map<String, Any?>> ?: continue
            val sectionMap = entries.getOrPut(section) { mutableMapOf() }
            for (e in sectionEntries) {
                // Handle proxy entries
                val pk = proxyKey(e)
                val typeName = e["type"] as? String
                when {
                    pk != null -> proxies.add(pk)
                    typeName != null -> {
                        val existing = sectionMap[typeName]
                        if (existing == null) {
                            sectionMap[typeName] = e.toMutableMap()
                        } else {
                            mergeTypeEntryInto(e, existing)
                        }
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        val resources = root["resources"] as? List<Map<String, Any?>> ?: return
        for (e in resources) {
            resourceJsons.add(JsonOutput.toJson(e))
            val glob = e["glob"] as? String
            if (glob != null) {
                val module = e["module"] as? String
                resourceGlobs.add(Pair(module, glob))
            }
        }
    }

    /** Adds the `main(String[])` entry point of [mainClass] to the reflection baseline. */
    fun addMainClass(mainClass: String) {
        val reflectionMap = entries.getOrPut("reflection") { mutableMapOf() }
        val mainClassEntry =
            mutableMapOf<String, Any?>(
                "type" to mainClass,
                "jniAccessible" to true,
                "methods" to
                    listOf(
                        mapOf("name" to "main", "parameterTypes" to listOf("java.lang.String[]")),
                    ),
            )
        val existing = reflectionMap[mainClass]
        if (existing == null) {
            reflectionMap[mainClass] = mainClassEntry
        } else {
            mergeTypeEntryInto(mainClassEntry, existing)
        }
    }

    /**
     * The disposition label (`[section] name`) when the baseline covers [projectEntry] of
     * [projectSection], or null when the entry must be kept. Same section is checked first,
     * then the other one.
     */
    fun coverageLabel(
        projectSection: String,
        projectEntry: Map<String, Any?>,
    ): String? {
        // Handle proxy entries: {"type": {"proxy": [...]}}
        val pk = proxyKey(projectEntry)
        if (pk != null) {
            return if (pk in proxies) "[$projectSection proxy] $pk" else null
        }

        val typeName = projectEntry["type"] as? String ?: return null

        val coveringSection =
            TYPE_SECTIONS.firstOrNull { baselineSection ->
                val libEntry = entries[baselineSection]?.get(typeName)
                libEntry != null && libraryCoversProjectEntry(libEntry, projectEntry)
            }
        if (coveringSection != null) {
            val source =
                if (coveringSection == projectSection) {
                    projectSection
                } else {
                    "$projectSection via $coveringSection"
                }
            return "[$source] $typeName"
        }

        // For Kotlin data objects: tracing agent emits Foo$Companion with serializer(),
        // but the actual class is Foo (no Companion class exists). If the parent class
        // is in the baseline with serializer(), consider the Companion entry covered.
        if (typeName.endsWith("\$Companion") && hasSerializer(typeName.removeSuffix("\$Companion"))) {
            return "[$projectSection via parent] $typeName"
        }

        return null
    }

    private fun hasSerializer(parentType: String): Boolean =
        TYPE_SECTIONS.any { baselineSection ->
            @Suppress("UNCHECKED_CAST")
            val parentMethods = entries[baselineSection]?.get(parentType)?.get("methods") as? List<Map<String, Any?>>
            parentMethods?.any { it["name"] == "serializer" } == true
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
        val name = (obj["name"] as? String).orEmpty()

        @Suppress("UNCHECKED_CAST")
        val params = (obj["parameterTypes"] as? List<String>)?.joinToString(",").orEmpty()
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
        if (broadFlags.any { flag -> projectEntry[flag] == true && libEntry[flag] != true }) return false

        return listOf("methods", "fields", "queriedMethods").all { memberKey ->
            @Suppress("UNCHECKED_CAST")
            val projectMembers = projectEntry[memberKey] as? List<Map<String, Any?>>
            val allDeclaredKey = if (memberKey == "fields") "allDeclaredFields" else "allDeclaredMethods"

            @Suppress("UNCHECKED_CAST")
            val libMembers = libEntry[memberKey] as? List<Map<String, Any?>>
            when {
                projectMembers.isNullOrEmpty() -> true
                libEntry[allDeclaredKey] == true -> true
                libMembers == null -> false
                else -> {
                    val libSigs = libMembers.map { sig(it) }.toSet()
                    projectMembers.all { sig(it) in libSigs }
                }
            }
        }
    }

    /** True if a resource entry is covered by the baseline. */
    fun isResourceEntryCovered(entry: Map<String, Any?>): Boolean {
        if (JsonOutput.toJson(entry) in resourceJsons) return true

        val glob = entry["glob"] as? String ?: return false
        val module = entry["module"] as? String

        for ((libModule, libGlob) in resourceGlobs) {
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
