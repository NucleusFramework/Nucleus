package dev.nucleusframework.nucleus.desktop.application.internal

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.jar.JarFile

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
 * Run with: `./gradlew cleanupGraalvmMetadata`
 */
@DisableCachingByDefault(because = "Modifies user source files in-place")
@Suppress("MaxLineLength", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
abstract class CleanupGraalvmMetadataTask : DefaultTask() {
    /** Runtime classpath JARs (for L1 library metadata + native-image.properties). */
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

    private val slurper = JsonSlurper()

    @TaskAction
    fun cleanup() {
        val targetDir = configDir.get()
        val targetFile = File(targetDir, "reachability-metadata.json")
        if (!targetFile.exists()) {
            logger.lifecycle("No reachability-metadata.json found in $targetDir — nothing to clean up")
            return
        }

        // Collect baseline entries from all managed sources
        val libraryEntries = mutableMapOf<String, MutableMap<String, MutableMap<String, Any?>>>()
        val libraryProxies = mutableSetOf<String>()
        val libraryResourceJsons = mutableSetOf<String>()
        val libraryResourceGlobs = mutableListOf<Pair<String?, String>>()
        val includeResourcePatterns = mutableListOf<Regex>()

        val l1Count = collectL1Metadata(libraryEntries, libraryProxies, libraryResourceJsons, libraryResourceGlobs, includeResourcePatterns)
        val l2Count = collectL2Metadata(libraryEntries, libraryProxies, libraryResourceJsons, libraryResourceGlobs)
        val l3Count = collectL3Metadata(libraryEntries, libraryProxies, libraryResourceJsons, libraryResourceGlobs)
        val staticCount = collectStaticAnalysisMetadata(libraryEntries, libraryProxies, libraryResourceJsons, libraryResourceGlobs)

        addMainClassToBaseline(libraryEntries)

        logger.lifecycle(
            "Metadata baseline: L1=$l1Count types, L2=$l2Count types, " +
                "L3=$l3Count types, static=$staticCount types, " +
                "${includeResourcePatterns.size} resource patterns",
        )

        // Parse the project's manual config
        @Suppress("UNCHECKED_CAST")
        val targetRoot = slurper.parseText(targetFile.readText()) as MutableMap<String, Any?>
        val removedEntries = mutableListOf<String>()

        val totalRemoved =
            cleanReflectionAndJni(targetRoot, libraryEntries, libraryProxies, removedEntries) +
                cleanResources(targetRoot, libraryResourceJsons, libraryResourceGlobs, includeResourcePatterns, removedEntries)

        if (totalRemoved > 0) {
            finalizeAndWrite(targetRoot, targetFile, totalRemoved, removedEntries)
        } else {
            logger.lifecycle("No redundant entries found — manual config is already clean")
        }

        reportRemaining(targetRoot)
    }

    private fun collectL1Metadata(
        libraryEntries: MutableMap<String, MutableMap<String, MutableMap<String, Any?>>>,
        libraryProxies: MutableSet<String>,
        libraryResourceJsons: MutableSet<String>,
        libraryResourceGlobs: MutableList<Pair<String?, String>>,
        includeResourcePatterns: MutableList<Regex>,
    ): Int {
        var l1Count = 0
        for (file in runtimeClasspath.files) {
            if (!file.exists() || !file.name.endsWith(".jar")) continue
            try {
                JarFile(file).use { jar ->
                    for (entry in jar.entries()) {
                        if (entry.name.contains("META-INF/native-image/") &&
                            entry.name.endsWith("reachability-metadata.json")
                        ) {
                            val text = jar.getInputStream(entry).bufferedReader().readText()
                            val before = countBaselineTypes(libraryEntries)
                            collectBaseline(text, libraryEntries, libraryProxies, libraryResourceJsons, libraryResourceGlobs)
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
        return l1Count
    }

    private fun collectL2Metadata(
        libraryEntries: MutableMap<String, MutableMap<String, MutableMap<String, Any?>>>,
        libraryProxies: MutableSet<String>,
        libraryResourceJsons: MutableSet<String>,
        libraryResourceGlobs: MutableList<Pair<String?, String>>,
    ): Int {
        var l2Count = 0
        val repoDirsFile = metadataRepoDirsFile.orNull?.asFile
        if (repoDirsFile != null && repoDirsFile.exists()) {
            val dirs = repoDirsFile.readText().trim()
            for (dirPath in dirs.lines()) {
                if (dirPath.isBlank()) continue
                val dir = File(dirPath)
                if (!dir.isDirectory) continue

                l2Count += collectL2FromDir(dir, libraryEntries, libraryProxies, libraryResourceJsons, libraryResourceGlobs)
            }
        }
        return l2Count
    }

    private fun collectL2FromDir(
        dir: File,
        libraryEntries: MutableMap<String, MutableMap<String, MutableMap<String, Any?>>>,
        libraryProxies: MutableSet<String>,
        libraryResourceJsons: MutableSet<String>,
        libraryResourceGlobs: MutableList<Pair<String?, String>>,
    ): Int {
        var count = 0
        // New format
        val newFormatFile = File(dir, "reachability-metadata.json")
        if (newFormatFile.exists()) {
            val before = countBaselineTypes(libraryEntries)
            collectBaseline(
                newFormatFile.readText(),
                libraryEntries,
                libraryProxies,
                libraryResourceJsons,
                libraryResourceGlobs,
            )
            count += countBaselineTypes(libraryEntries) - before
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
                    count++
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
        return count
    }

    private fun collectL3Metadata(
        libraryEntries: MutableMap<String, MutableMap<String, MutableMap<String, Any?>>>,
        libraryProxies: MutableSet<String>,
        libraryResourceJsons: MutableSet<String>,
        libraryResourceGlobs: MutableList<Pair<String?, String>>,
    ): Int {
        val platform = platformName.get()
        val resourcePath = "nucleus/graalvm/platform-metadata/$platform-reachability-metadata.json"
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
        if (stream != null) {
            val text = stream.bufferedReader().use { it.readText() }
            val before = countBaselineTypes(libraryEntries)
            collectBaseline(text, libraryEntries, libraryProxies, libraryResourceJsons, libraryResourceGlobs)
            return countBaselineTypes(libraryEntries) - before
        }
        return 0
    }

    private fun collectStaticAnalysisMetadata(
        libraryEntries: MutableMap<String, MutableMap<String, MutableMap<String, Any?>>>,
        libraryProxies: MutableSet<String>,
        libraryResourceJsons: MutableSet<String>,
        libraryResourceGlobs: MutableList<Pair<String?, String>>,
    ): Int {
        var staticCount = 0
        for (dir in staticAnalysisDir.files) {
            val staticFile = if (dir.isDirectory) File(dir, "reachability-metadata.json") else dir
            if (staticFile.exists() && staticFile.name.endsWith(".json")) {
                val before = countBaselineTypes(libraryEntries)
                collectBaseline(
                    staticFile.readText(),
                    libraryEntries,
                    libraryProxies,
                    libraryResourceJsons,
                    libraryResourceGlobs,
                )
                staticCount += countBaselineTypes(libraryEntries) - before
            }
        }
        return staticCount
    }

    private fun addMainClassToBaseline(libraryEntries: MutableMap<String, MutableMap<String, MutableMap<String, Any?>>>) {
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
    }

    private fun cleanReflectionAndJni(
        targetRoot: MutableMap<String, Any?>,
        libraryEntries: Map<String, Map<String, MutableMap<String, Any?>>>,
        libraryProxies: Set<String>,
        removedEntries: MutableList<String>,
    ): Int {
        var totalRemoved = 0
        for (projectSection in listOf("reflection", "jni")) {
            @Suppress("UNCHECKED_CAST")
            val targetArray = targetRoot[projectSection] as? MutableList<Map<String, Any?>> ?: continue
            val before = targetArray.size

            targetArray.removeAll { projectEntry ->
                if (isEntryCovered(projectSection, projectEntry, libraryEntries, libraryProxies, removedEntries)) {
                    return@removeAll true
                }
                false
            }
            totalRemoved += before - targetArray.size
        }
        return totalRemoved
    }

    private fun isEntryCovered(
        projectSection: String,
        projectEntry: Map<String, Any?>,
        libraryEntries: Map<String, Map<String, MutableMap<String, Any?>>>,
        libraryProxies: Set<String>,
        removedEntries: MutableList<String>,
    ): Boolean {
        // Handle proxy entries: {"type": {"proxy": [...]}}
        val proxyKey = proxyKey(projectEntry)
        if (proxyKey != null) {
            if (proxyKey in libraryProxies) {
                removedEntries.add("  [$projectSection proxy] $proxyKey")
                return true
            }
            return false
        }

        val typeName = projectEntry["type"] as? String ?: return false

        // Check same section first, then cross-section
        for (baselineSection in listOf("reflection", "jni")) {
            val sectionMap = libraryEntries[baselineSection] ?: continue
            val libEntry = sectionMap[typeName] ?: continue
            if (libraryCoversProjectEntry(libEntry, projectEntry)) {
                val source = if (baselineSection == projectSection) baselineSection else "$projectSection via $baselineSection"
                removedEntries.add("  [$source] $typeName")
                return true
            }
        }

        // For Kotlin data objects
        if (typeName.endsWith("\$Companion")) {
            val parentType = typeName.removeSuffix("\$Companion")
            for (baselineSection in listOf("reflection", "jni")) {
                val sectionMap = libraryEntries[baselineSection] ?: continue
                val parentEntry = sectionMap[parentType] ?: continue

                @Suppress("UNCHECKED_CAST")
                val parentMethods = parentEntry["methods"] as? List<Map<String, Any?>>
                val hasSerializer = parentMethods?.any { it["name"] == "serializer" } == true
                if (hasSerializer) {
                    removedEntries.add("  [$projectSection via parent] $typeName")
                    return true
                }
            }
        }
        return false
    }

    private fun cleanResources(
        targetRoot: MutableMap<String, Any?>,
        libraryResourceJsons: Set<String>,
        libraryResourceGlobs: List<Pair<String?, String>>,
        includeResourcePatterns: List<Regex>,
        removedEntries: MutableList<String>,
    ): Int {
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
                    removedEntries.add("  [resource] $glob")
                }
                covered
            }
            return before - targetResources.size
        }
        return 0
    }

    private fun finalizeAndWrite(
        targetRoot: MutableMap<String, Any?>,
        targetFile: File,
        totalRemoved: Int,
        removedEntries: List<String>,
    ) {
        // Remove empty sections
        for (section in listOf("reflection", "jni", "resources", "bundles", "serialization")) {
            @Suppress("UNCHECKED_CAST")
            val arr = targetRoot[section] as? List<*>
            if (arr != null && arr.isEmpty()) {
                targetRoot.remove(section)
            }
        }
        targetFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(targetRoot)) + "\n")
        logger.lifecycle("Removed $totalRemoved entries from $targetFile:")
        removedEntries.forEach { logger.lifecycle(it) }
    }

    private fun reportRemaining(targetRoot: Map<String, Any?>) {
        var remaining = 0
        for (section in listOf("reflection", "jni")) {
            @Suppress("UNCHECKED_CAST")
            val arr = targetRoot[section] as? List<*>
            remaining += arr?.size ?: 0
        }
        @Suppress("UNCHECKED_CAST")
        val resArr = targetRoot["resources"] as? List<*>
        remaining += resArr?.size ?: 0
        logger.lifecycle("Remaining manual entries: $remaining")
    }

    private fun countBaselineTypes(entries: Map<String, Map<String, MutableMap<String, Any?>>>): Int = entries.values.sumOf { it.size }

    /** Extract a canonical key for proxy entries, or null if not a proxy. */
    @Suppress("UNCHECKED_CAST")
    private fun proxyKey(entry: Map<String, Any?>): String? {
        val typeValue = entry["type"] as? Map<String, Any?> ?: return null
        val proxyList = typeValue["proxy"] as? List<String> ?: return null
        return proxyList.sorted().joinToString(",")
    }

    private fun collectBaseline(
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
