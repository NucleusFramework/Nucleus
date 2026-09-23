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

private const val LIBRARY_METADATA_DIR = "nucleus/graalvm/library-metadata"

/**
 * JDK types whose reflection registration a layered image moves from the application layer to the
 * base layer.
 *
 * Registering a type makes its fields queryable, so the application layer's analysis checks every
 * reachable subtype for a field hiding one of them. `MethodHandle`'s subtypes include the
 * `BoundMethodHandle$Species_*_BaseLayer` classes generated while the base layer is built, which
 * the application layer only knows as incomplete `BaseLayerType`s: when one of them turns reachable
 * before the check runs, the build aborts with "This type is incomplete and should not be used" —
 * about every other build on GraalVM 25.4. The base layer holds complete types, so the same
 * registration made there is checked without the race, and the application still sees it.
 */
internal val BASE_LAYER_REFLECTION_TYPES = setOf("java.lang.invoke.MethodHandle")

/**
 * The `reachability-metadata.json` content that registers [BASE_LAYER_REFLECTION_TYPES] in the
 * base layer: their entries as the library metadata declares them.
 */
internal fun baseLayerReflectionMetadata(): String {
    val entries =
        readLibraryMetadataIndex().flatMap { fileName ->
            @Suppress("UNCHECKED_CAST")
            (readLibraryMetadata(fileName)?.get("reflection") as? List<Map<String, Any?>>)
                .orEmpty()
                .filter { it["type"] in BASE_LAYER_REFLECTION_TYPES }
        }
    return JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("reflection" to entries))) + "\n"
}

private fun readLibraryMetadataIndex(): List<String> =
    FilterLibraryMetadataTask::class.java.classLoader
        .getResourceAsStream("$LIBRARY_METADATA_DIR/index.txt")
        ?.bufferedReader()
        ?.readLines()
        ?.filter { it.isNotBlank() }
        ?: emptyList()

@Suppress("UNCHECKED_CAST")
private fun readLibraryMetadata(fileName: String): Map<String, Any?>? =
    FilterLibraryMetadataTask::class.java.classLoader
        .getResourceAsStream("$LIBRARY_METADATA_DIR/$fileName")
        ?.bufferedReader()
        ?.use { JsonSlurper().parseText(it.readText()) as Map<String, Any?> }

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

    /**
     * Whether a base layer registers [BASE_LAYER_REFLECTION_TYPES], in which case their entries are
     * left out here. False for a monolithic image.
     */
    @get:Input
    abstract val baseLayerOwnsReflectionTypes: Property<Boolean>

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

        val index = readLibraryMetadataIndex()
        val movedToBaseLayer = baseLayerOwnsReflectionTypes.get()

        val mergedReflection = mutableListOf<Any?>()
        val mergedResources = mutableListOf<Any?>()
        var includedCount = 0
        var skippedCount = 0

        val skipGuiMetadata = headless.get()
        for (fileName in index) {
            if (skipGuiMetadata && fileName in HEADLESS_SKIP_METADATA) {
                skippedCount++
                continue
            }
            val root = readLibraryMetadata(fileName) ?: continue

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
            if (reflection != null) {
                mergedReflection.addAll(
                    if (movedToBaseLayer) {
                        reflection.filterNot { (it as? Map<*, *>)?.get("type") in BASE_LAYER_REFLECTION_TYPES }
                    } else {
                        reflection
                    },
                )
            }

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
