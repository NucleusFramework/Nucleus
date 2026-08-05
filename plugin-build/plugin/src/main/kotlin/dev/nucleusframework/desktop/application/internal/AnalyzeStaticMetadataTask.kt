package dev.nucleusframework.desktop.application.internal

import groovy.json.JsonOutput
import dev.nucleusframework.desktop.application.internal.analyzer.BytecodeAnalyzer
import dev.nucleusframework.desktop.application.internal.analyzer.JniEntry
import dev.nucleusframework.desktop.application.internal.analyzer.MethodSignature
import dev.nucleusframework.desktop.application.internal.analyzer.ReflectionEntry
import dev.nucleusframework.desktop.application.internal.analyzer.ResourcePattern
import dev.nucleusframework.desktop.application.internal.analyzer.detectors.ClassReferenceCollector
import dev.nucleusframework.desktop.application.internal.analyzer.detectors.OrphanProjectClassDetector
import dev.nucleusframework.desktop.application.internal.analyzer.mergeReflectionEntries
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.jar.JarFile

/**
 * Statically analyzes bytecode in all runtime classpath JARs and generates
 * GraalVM reachability metadata (reflection, JNI, resources) that can be
 * detected without running the application.
 *
 * The output directory contains a `reachability-metadata.json` file in the
 * standard GraalVM format, ready to be passed as `-H:ConfigurationFileDirectories=`.
 */
@CacheableTask
abstract class AnalyzeStaticMetadataTask : DefaultTask() {
    /** The runtime classpath JARs to analyze. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeClasspath: ConfigurableFileCollection

    /**
     * The project's own compiled class directories (e.g. `build/classes/kotlin/main`).
     * Used by the orphan-project-class detector (#441); must not include dependency JARs.
     * Entries may overlap with [runtimeClasspath] — that is fine.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectClassDirs: ConfigurableFileCollection

    /**
     * When true (default), register a public no-arg `<init>` for project classes that no
     * bytecode references — the generic fix for Room `_Impl` and friends. See #441.
     */
    @get:Input
    abstract val detectOrphanProjectClasses: Property<Boolean>

    /**
     * When true, register a public no-arg `<init>` for every project class that has one.
     * Opt-in sledgehammer; implies a larger image. See #441.
     */
    @get:Input
    abstract val reflectionForProjectClasses: Property<Boolean>

    /** Output directory where reachability-metadata.json is written. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun analyze() {
        val classpathEntries = runtimeClasspath.files.filter { it.exists() }
        val jars = classpathEntries.filter { it.name.endsWith(".jar") }
        val classDirs = classpathEntries.filter { it.isDirectory }
        if (jars.isEmpty() && classDirs.isEmpty()) {
            logger.info("No JARs or class directories to analyze for static metadata")
            return
        }

        logger.lifecycle(
            "Static bytecode analysis: scanning ${jars.size} JARs" +
                if (classDirs.isNotEmpty()) " + ${classDirs.size} class directories" else "",
        )

        val result = BytecodeAnalyzer.analyzeClasspath(classpathEntries)

        val projectDirs = projectClassDirs.files.filter { it.isDirectory && it.exists() }
        val orphanEnabled = detectOrphanProjectClasses.getOrElse(true)
        val allProjectEnabled = reflectionForProjectClasses.getOrElse(false)

        val projectClassBytes =
            if (orphanEnabled || allProjectEnabled) {
                indexClassDirs(projectDirs)
            } else {
                emptyMap()
            }

        val projectReflection =
            when {
                allProjectEnabled -> {
                    val all = OrphanProjectClassDetector.detectAllProjectClasses(projectClassBytes)
                    logProjectEntries("project-class", all)
                    all
                }
                orphanEnabled && projectClassBytes.isNotEmpty() -> {
                    val (classpathRefs, appRefs) = collectReferenceSets(classpathEntries, projectDirs)
                    val orphans =
                        OrphanProjectClassDetector.detect(
                            projectClassBytes = projectClassBytes,
                            classpathReferencedTypes = classpathRefs,
                            appReferencedTypes = appRefs,
                        )
                    logProjectEntries("orphan", orphans)
                    orphans
                }
                else -> emptySet()
            }

        val allReflection = mergeReflectionEntries(result.allReflectionEntries + projectReflection)
        val jniEntries = result.jniEntries
        val resources = result.resourcePatterns

        logger.lifecycle(
            "Static analysis found: " +
                "${allReflection.size} reflection, " +
                "${jniEntries.size} JNI, " +
                "${resources.size} resource entries" +
                if (projectReflection.isNotEmpty()) {
                    " (${projectReflection.size} from project-class detector)"
                } else {
                    ""
                },
        )

        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        val json = buildReachabilityMetadataJson(allReflection, jniEntries, resources)
        File(outDir, "reachability-metadata.json").writeText(json)

        logger.lifecycle("Static metadata written to: $outDir")
    }

    private fun logProjectEntries(
        tag: String,
        entries: Set<ReflectionEntry>,
    ) {
        logger.lifecycle(
            "Project-class detector ($tag): ${entries.size} entr" +
                if (entries.size == 1) "y" else "ies",
        )
        for (entry in entries.sortedBy { it.type }) {
            // Auditable set for #441 — keep the prefix stable so grepping build logs is easy.
            logger.lifecycle("[$tag] ${entry.type}")
        }
    }

    /**
     * @return pair of (full-classpath referenced FQCNs, app-only referenced FQCNs)
     */
    private fun collectReferenceSets(
        classpathEntries: Collection<File>,
        projectDirs: Collection<File>,
    ): Pair<Set<String>, Set<String>> {
        val classpathRefs = mutableSetOf<String>()
        val appRefs = mutableSetOf<String>()
        val projectDirSet = projectDirs.map { it.canonicalFile }.toSet()

        for (file in classpathEntries) {
            when {
                file.isDirectory -> {
                    val canonical = file.canonicalFile
                    val isProject =
                        canonical in projectDirSet ||
                            projectDirSet.any { projectDir ->
                                canonical.toPath().startsWith(projectDir.toPath())
                            }
                    forEachClassBytes(file) { bytes ->
                        val refs = ClassReferenceCollector.collect(bytes)
                        classpathRefs.addAll(refs)
                        if (isProject) appRefs.addAll(refs)
                    }
                }
                file.isFile && file.name.endsWith(".jar") -> {
                    forEachJarClassBytes(file) { bytes ->
                        classpathRefs.addAll(ClassReferenceCollector.collect(bytes))
                    }
                }
            }
        }
        // Project dirs may not all be on runtimeClasspath (shouldn't happen, but be safe)
        for (dir in projectDirs) {
            forEachClassBytes(dir) { bytes ->
                val refs = ClassReferenceCollector.collect(bytes)
                classpathRefs.addAll(refs)
                appRefs.addAll(refs)
            }
        }
        return classpathRefs to appRefs
    }
}

/**
 * Indexes `.class` files under [dirs] as internal name → bytes.
 */
internal fun indexClassDirs(dirs: Collection<File>): Map<String, ByteArray> {
    val index = mutableMapOf<String, ByteArray>()
    for (dir in dirs) {
        if (!dir.isDirectory) continue
        dir
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { classFile ->
                val relative = classFile.relativeTo(dir).path
                // Normalize Windows separators
                val internalName = relative.removeSuffix(".class").replace('\\', '/')
                try {
                    index[internalName] = classFile.readBytes()
                } catch (_: Exception) {
                    // unreadable class file — skip
                }
            }
    }
    return index
}

private fun forEachClassBytes(
    dir: File,
    action: (ByteArray) -> Unit,
) {
    if (!dir.isDirectory) return
    dir
        .walkTopDown()
        .filter { it.isFile && it.extension == "class" }
        .forEach { classFile ->
            try {
                action(classFile.readBytes())
            } catch (_: Exception) {
                // skip
            }
        }
}

private fun forEachJarClassBytes(
    jarPath: File,
    action: (ByteArray) -> Unit,
) {
    try {
        JarFile(jarPath).use { jar ->
            for (entry in jar.entries()) {
                if (!entry.name.endsWith(".class") || entry.name.startsWith("META-INF/")) continue
                try {
                    action(jar.getInputStream(entry).use { it.readBytes() })
                } catch (_: Exception) {
                    // skip
                }
            }
        }
    } catch (_: Exception) {
        // corrupt JAR — skip
    }
}

/**
 * Builds a reachability-metadata.json string in the GraalVM format from analysis results.
 */
internal fun buildReachabilityMetadataJson(
    reflectionEntries: Set<ReflectionEntry>,
    jniEntries: Set<JniEntry>,
    resourcePatterns: Set<ResourcePattern>,
): String {
    val root = mutableMapOf<String, Any>()

    if (reflectionEntries.isNotEmpty()) {
        root["reflection"] =
            reflectionEntries
                .sortedBy { it.type }
                .map { it.toJsonMap() }
    }

    if (jniEntries.isNotEmpty()) {
        root["jni"] =
            jniEntries
                .sortedBy { it.type }
                .map { it.toJsonMap() }
    }

    if (resourcePatterns.isNotEmpty()) {
        root["resources"] =
            resourcePatterns
                .sortedBy { it.glob ?: it.bundle ?: "" }
                .map { it.toJsonMap() }
    }

    return JsonOutput.prettyPrint(JsonOutput.toJson(root)) + "\n"
}

private fun ReflectionEntry.toJsonMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>("type" to type)
    if (allDeclaredFields) map["allDeclaredFields"] = true
    if (allDeclaredMethods) map["allDeclaredMethods"] = true
    if (allDeclaredConstructors) map["allDeclaredConstructors"] = true
    if (allPublicFields) map["allPublicFields"] = true
    if (allPublicMethods) map["allPublicMethods"] = true
    if (allPublicConstructors) map["allPublicConstructors"] = true
    if (unsafeAllocated) map["unsafeAllocated"] = true
    if (methods.isNotEmpty()) {
        map["methods"] = methods.sortedBy { it.name }.map { it.toJsonMap() }
    }
    if (fields.isNotEmpty()) {
        map["fields"] = fields.sorted().map { mapOf("name" to it) }
    }
    return map
}

private fun JniEntry.toJsonMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>("type" to type)
    if (jniAccessible) map["jniAccessible"] = true
    if (methods.isNotEmpty()) {
        map["methods"] = methods.sortedBy { it.name }.map { it.toJsonMap() }
    }
    if (fields.isNotEmpty()) {
        map["fields"] = fields.sorted().map { mapOf("name" to it) }
    }
    return map
}

private fun MethodSignature.toJsonMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>("name" to name)
    if (parameterTypes.isNotEmpty()) {
        map["parameterTypes"] = parameterTypes
    }
    return map
}

private fun ResourcePattern.toJsonMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    if (glob != null) map["glob"] = glob
    if (bundle != null) map["bundle"] = bundle
    if (module != null) map["module"] = module
    return map
}
