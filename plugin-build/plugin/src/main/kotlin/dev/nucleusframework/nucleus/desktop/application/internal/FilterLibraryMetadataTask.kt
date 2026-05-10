package dev.nucleusframework.nucleus.desktop.application.internal

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
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
import java.util.jar.JarFile

/**
 * Filters per-library GraalVM metadata based on the runtime classpath and merges
 * the result into a single `reachability-metadata.json`.
 *
 * Each per-library file lives in `nucleus/graalvm/library-metadata/` inside the plugin
 * JAR and may declare `_meta.matchPackages`. If present, the file is only included when
 * at least one classpath JAR contains classes under one of those package prefixes.
 * Files without `matchPackages` are always included.
 */
@CacheableTask
@Suppress("NestedBlockDepth", "LoopWithTooManyJumpStatements")
abstract class FilterLibraryMetadataTask : DefaultTask() {
    /** The runtime classpath JARs/dirs to check for conditional library presence. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeClasspath: ConfigurableFileCollection

    /** Output directory where the merged `reachability-metadata.json` is written. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun filter() {
        val classpathPackages = buildClasspathPackageIndex(runtimeClasspath.files)

        val metadataDir = "nucleus/graalvm/library-metadata"
        val index =
            javaClass.classLoader
                .getResourceAsStream("$metadataDir/index.txt")
                ?.bufferedReader()
                ?.readLines()
                ?.filter { it.isNotBlank() }
                ?: emptyList()

        val slurper = JsonSlurper()
        val mergedReflection = mutableListOf<Any?>()
        val mergedResources = mutableListOf<Any?>()
        var includedCount = 0
        var skippedCount = 0

        for (fileName in index) {
            val stream = javaClass.classLoader.getResourceAsStream("$metadataDir/$fileName") ?: continue

            @Suppress("UNCHECKED_CAST")
            val root = slurper.parseText(stream.bufferedReader().use { it.readText() }) as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val meta = root["_meta"] as? Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val matchPackages = meta?.get("matchPackages") as? List<String>

            if (matchPackages != null) {
                val found = matchPackages.any { prefix -> classpathPackages.any { it.startsWith(prefix) } }
                if (!found) {
                    skippedCount++
                    logger.info("Skipping $fileName: no matching packages on classpath")
                    continue
                }
            }

            includedCount++

            @Suppress("UNCHECKED_CAST")
            val reflection = root["reflection"] as? List<Any?>
            if (reflection != null) mergedReflection.addAll(reflection)

            @Suppress("UNCHECKED_CAST")
            val resources = root["resources"] as? List<Any?>
            if (resources != null) mergedResources.addAll(resources)
        }

        val merged = mutableMapOf<String, Any?>()
        if (mergedReflection.isNotEmpty()) merged["reflection"] = mergedReflection
        if (mergedResources.isNotEmpty()) merged["resources"] = mergedResources

        val outDir = outputDir.get().asFile
        outDir.mkdirs()
        File(outDir, "reachability-metadata.json")
            .writeText(JsonOutput.prettyPrint(JsonOutput.toJson(merged)) + "\n")

        logger.lifecycle(
            "Library metadata: included $includedCount files, skipped $skippedCount conditional files",
        )
    }
}

/**
 * Scans classpath JARs and class directories to build a set of all Java package names present.
 * Only reads the ZIP central directory (fast) — no class file parsing.
 */
private fun buildClasspathPackageIndex(files: Set<File>): Set<String> {
    val packages = mutableSetOf<String>()
    for (file in files) {
        if (!file.exists()) continue
        if (file.isDirectory) {
            file
                .walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { classFile ->
                    val relative = classFile.relativeTo(file).path
                    val pkg = relative.substringBeforeLast('/', "").replace('/', '.')
                    if (pkg.isNotEmpty()) packages.add(pkg)
                }
        } else if (file.name.endsWith(".jar")) {
            try {
                JarFile(file).use { jar ->
                    for (entry in jar.entries()) {
                        if (entry.name.endsWith(".class") && !entry.isDirectory) {
                            val pkg = entry.name.substringBeforeLast('/', "").replace('/', '.')
                            if (pkg.isNotEmpty()) packages.add(pkg)
                        }
                    }
                }
            } catch (_: Exception) {
                // Skip unreadable JARs
            }
        }
    }
    return packages
}
