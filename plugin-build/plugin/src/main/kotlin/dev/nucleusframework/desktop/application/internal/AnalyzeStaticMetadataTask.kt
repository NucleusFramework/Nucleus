package dev.nucleusframework.desktop.application.internal

import groovy.json.JsonOutput
import dev.nucleusframework.desktop.application.internal.analyzer.BytecodeAnalyzer
import dev.nucleusframework.desktop.application.internal.analyzer.JniEntry
import dev.nucleusframework.desktop.application.internal.analyzer.MethodSignature
import dev.nucleusframework.desktop.application.internal.analyzer.ReflectionEntry
import dev.nucleusframework.desktop.application.internal.analyzer.ResourcePattern
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

/**
 * Statically analyzes bytecode in all runtime classpath JARs and generates
 * GraalVM reachability metadata (reflection, JNI, resources) that can be
 * detected without running the application.
 *
 * The output directory contains a `reachability-metadata.json` file in the
 * standard GraalVM format, ready to be passed as `-H:ConfigurationFileDirectories=`.
 *
 * Orphan / project-class detection (#441) is performed inside
 * [BytecodeAnalyzer.analyzeClasspath] in the same classpath walk as the other detectors.
 */
@CacheableTask
abstract class AnalyzeStaticMetadataTask : DefaultTask() {
    /** The runtime classpath JARs (and class directories) to analyze. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeClasspath: ConfigurableFileCollection

    /**
     * The project's own compiled class directories (e.g. `build/classes/kotlin/main`).
     * Must not include dependency JARs. May overlap with [runtimeClasspath].
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
     * Opt-in sledgehammer; implies a larger image. Supersedes the orphan rule when both
     * are enabled (superset). See #441.
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

        val orphanEnabled = detectOrphanProjectClasses.getOrElse(true)
        val allProjectEnabled = reflectionForProjectClasses.getOrElse(false)
        val projectDirs = projectClassDirs.files.filter { it.isDirectory && it.exists() }

        val result =
            BytecodeAnalyzer.analyzeClasspath(
                files = classpathEntries,
                projectClassDirs = projectDirs,
                detectOrphanProjectClasses = orphanEnabled,
                reflectionForProjectClasses = allProjectEnabled,
            )

        val projectEntries = result.projectClassEntries
        if (orphanEnabled || allProjectEnabled) {
            val tag = if (allProjectEnabled) "project-class" else "orphan"
            logProjectEntries(tag, projectEntries)
        }

        val allReflection = result.allReflectionEntries
        val jniEntries = result.jniEntries
        val resources = result.resourcePatterns

        logger.lifecycle(
            "Static analysis found: " +
                "${allReflection.size} reflection, " +
                "${jniEntries.size} JNI, " +
                "${resources.size} resource entries" +
                if (projectEntries.isNotEmpty()) {
                    " (${projectEntries.size} from project-class detector)"
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
        // Cap log volume for the sledgehammer path (can register hundreds of types).
        val limit = if (tag == "project-class") 50 else Int.MAX_VALUE
        val sorted = entries.sortedBy { it.type }
        for (entry in sorted.take(limit)) {
            logger.lifecycle("[$tag] ${entry.type}")
        }
        if (sorted.size > limit) {
            logger.lifecycle("[$tag] … and ${sorted.size - limit} more")
        }
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
