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
import org.gradle.api.tasks.CacheableTask
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
 */
@CacheableTask
abstract class AnalyzeStaticMetadataTask : DefaultTask() {
    /** The runtime classpath JARs to analyze. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeClasspath: ConfigurableFileCollection

    /** Output directory where reachability-metadata.json is written. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun analyze() {
        // Always materialize the output directory so consumers can declare it as a
        // non-optional @InputDirectory / inputs.dir without fingerprint failures when
        // the classpath is empty (Gradle requires @OutputDirectory to exist after the task).
        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        val classpathEntries = runtimeClasspath.files.filter { it.exists() }
        val jars = classpathEntries.filter { it.name.endsWith(".jar") }
        val classDirs = classpathEntries.filter { it.isDirectory }
        if (jars.isEmpty() && classDirs.isEmpty()) {
            logger.info("No JARs or class directories to analyze for static metadata")
            File(outDir, "reachability-metadata.json").writeText("{}\n")
            return
        }

        logger.lifecycle(
            "Static bytecode analysis: scanning ${jars.size} JARs" +
                if (classDirs.isNotEmpty()) " + ${classDirs.size} class directories" else "",
        )

        val result = BytecodeAnalyzer.analyzeClasspath(classpathEntries)

        val allReflection = result.allReflectionEntries
        val jniEntries = result.jniEntries
        val resources = result.resourcePatterns

        logger.lifecycle(
            "Static analysis found: " +
                "${allReflection.size} reflection, " +
                "${jniEntries.size} JNI, " +
                "${resources.size} resource entries",
        )

        val json = buildReachabilityMetadataJson(allReflection, jniEntries, resources)
        File(outDir, "reachability-metadata.json").writeText(json)

        logger.lifecycle("Static metadata written to: $outDir")
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
