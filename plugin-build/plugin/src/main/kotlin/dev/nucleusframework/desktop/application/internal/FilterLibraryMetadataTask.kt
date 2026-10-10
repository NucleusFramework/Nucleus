package dev.nucleusframework.desktop.application.internal

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
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
 * Filters per-library GraalVM metadata based on the runtime classpath and merges
 * the result into a single `reachability-metadata.json`.
 *
 * Each per-library file lives in `nucleus/graalvm/library-metadata/` inside the plugin
 * JAR and may declare `_meta.matchPackages`. If present, the file is only included when
 * at least one classpath JAR contains classes under one of those package prefixes.
 * Files without `matchPackages` are always included.
 */
@CacheableTask
abstract class FilterLibraryMetadataTask : DefaultTask() {
    @get:Input
    abstract val headless: Property<Boolean>

    /** The runtime classpath JARs/dirs to check for conditional library presence. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeClasspath: ConfigurableFileCollection

    /** Output directory where the merged `reachability-metadata.json` is written. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /**
     * Merges the `reflection` and `resources` sections of every bundled per-library
     * metadata file whose `_meta.matchPackages` match [runtimeClasspath] into
     * `reachability-metadata.json` in [outputDir]. GUI-only files are skipped when
     * [headless] is set.
     */
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
                .orEmpty()

        val slurper = JsonSlurper()
        val mergedReflection = mutableListOf<Any?>()
        val mergedResources = mutableListOf<Any?>()
        var includedCount = 0

        val skipGuiMetadata = headless.get()
        val (headlessSkipped, candidates) = index.partition { skipGuiMetadata && it in HEADLESS_SKIP_METADATA }
        var skippedCount = headlessSkipped.size
        for (fileName in candidates) {
            val root = readLibraryMetadata(slurper, "$metadataDir/$fileName") ?: continue
            if (matchesClasspath(root, classpathPackages)) {
                includedCount++

                @Suppress("UNCHECKED_CAST")
                val reflection = root["reflection"] as? List<Any?>
                if (reflection != null) mergedReflection.addAll(reflection)

                @Suppress("UNCHECKED_CAST")
                val resources = root["resources"] as? List<Any?>
                if (resources != null) mergedResources.addAll(resources)
            } else {
                skippedCount++
                logger.info("Skipping $fileName: no matching packages on classpath")
            }
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

    private fun readLibraryMetadata(
        slurper: JsonSlurper,
        resourcePath: String,
    ): Map<String, Any?>? {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath) ?: return null

        @Suppress("UNCHECKED_CAST")
        val root = slurper.parseText(stream.bufferedReader().use { it.readText() }) as Map<String, Any?>
        return root
    }

    /** A file without `_meta.matchPackages` always matches. */
    private fun matchesClasspath(
        root: Map<String, Any?>,
        classpathPackages: Set<String>,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        val meta = root["_meta"] as? Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val matchPackages = meta?.get("matchPackages") as? List<String> ?: return true
        return matchPackages.any { prefix -> classpathPackages.any { it.startsWith(prefix) } }
    }

    /** Holds the per-library metadata files that only matter for GUI (non-headless) images. */
    companion object {
        private val HEADLESS_SKIP_METADATA =
            setOf(
                "jdk-awt.json",
                "jdk-fonts.json",
                "jdk-graphics2d.json",
                "skia-skiko.json",
                "composetray.json",
                "compose-ui.json",
                "compose-mediaplayer.json",
                "compose-webview-wry.json",
            )
    }
}
