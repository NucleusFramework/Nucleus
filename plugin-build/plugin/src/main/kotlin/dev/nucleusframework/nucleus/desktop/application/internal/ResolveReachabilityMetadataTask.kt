package dev.nucleusframework.nucleus.desktop.application.internal

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Resolves GraalVM reachability metadata from the Oracle repository for runtime dependencies.
 *
 * This task is configuration-cache compatible: all inputs are declared as Gradle properties
 * and file collections, so no `Project`/`Configuration` references are serialized.
 */
@DisableCachingByDefault(because = "Resolves external metadata repository; output depends on extracted ZIP contents")
abstract class ResolveReachabilityMetadataTask : DefaultTask() {
    @get:Input
    abstract val repoEnabled: Property<Boolean>

    @get:Input
    abstract val repoVersion: Property<String>

    @get:Input
    abstract val excludedModules: SetProperty<String>

    @get:Input
    abstract val moduleToConfigVersion: MapProperty<String, String>

    /** The metadata repository ZIP file(s) resolved from Maven. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metadataZipFiles: ConfigurableFileCollection

    /** The runtime classpath JARs — used to list dependency coordinates. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:Input
    abstract val outputDirsFile: Property<File>

    @get:Input
    abstract val extractionDir: Property<File>

    @TaskAction
    fun resolve() {
        val outFile = outputDirsFile.get()
        outFile.parentFile.mkdirs()

        if (!repoEnabled.get()) {
            outFile.writeText("")
            return
        }

        val zipFile = metadataZipFiles.files.firstOrNull()
        if (zipFile == null) {
            logger.warn("GraalVM reachability metadata repository ZIP not found")
            outFile.writeText("")
            return
        }

        // Build artifact info from classpath file names.
        // FileCollection doesn't carry Maven coordinates, so we parse artifact coordinates
        // from JAR file paths following the Gradle cache layout.
        val artifacts = parseArtifactsFromClasspath(runtimeClasspath.files)

        if (artifacts.isEmpty()) {
            logger.info("No runtime artifacts found, skipping metadata repository resolution")
            outFile.writeText("")
            return
        }

        val version = repoVersion.get()
        val excluded = excludedModules.getOrElse(emptySet())
        val overrides = moduleToConfigVersion.getOrElse(emptyMap())
        val outputDir = extractionDir.get()

        val dirs =
            resolveMetadataRepositoryFromArtifacts(
                zipFile = zipFile,
                version = version,
                excludedModules = excluded,
                moduleToConfigVersion = overrides,
                artifacts = artifacts,
                outputDir = outputDir,
                logger = logger,
            )

        outFile.writeText(dirs.joinToString("\n") { it.absolutePath })

        if (dirs.isNotEmpty()) {
            logger.lifecycle(
                "Resolved ${dirs.size} metadata entries from Oracle repository v$version",
            )
        }
    }
}

/**
 * Parse artifact coordinates from classpath JAR file paths.
 * Gradle caches JARs in: `~/.gradle/caches/modules-2/files-2.1/<group>/<name>/<version>/<hash>/<name>-<version>.jar`
 * Maven local stores in: `~/.m2/repository/<group-path>/<name>/<version>/<name>-<version>.jar`
 */
internal data class ArtifactCoordinates(
    val group: String,
    val name: String,
    val version: String,
)

internal fun parseArtifactsFromClasspath(files: Set<File>): List<ArtifactCoordinates> {
    val artifacts = mutableListOf<ArtifactCoordinates>()
    val seen = mutableSetOf<String>()

    for (file in files) {
        if (!file.name.endsWith(".jar")) continue
        val coords =
            parseGradleCacheCoordinates(file)
                ?: parseMavenLocalCoordinates(file)
                ?: continue
        val key = "${coords.group}:${coords.name}"
        if (key !in seen) {
            artifacts.add(coords)
            seen.add(key)
        }
    }
    return artifacts
}

/**
 * Gradle cache layout: `<cache-root>/modules-2/files-2.1/<group>/<name>/<version>/<hash>/<file>`
 */
private fun parseGradleCacheCoordinates(file: File): ArtifactCoordinates? {
    val parts = file.absolutePath.replace('\\', '/').split('/')
    val filesIdx = parts.indexOfLast { it == "files-2.1" }
    if (filesIdx < 0 || filesIdx + 4 >= parts.size) return null
    return ArtifactCoordinates(
        group = parts[filesIdx + 1],
        name = parts[filesIdx + 2],
        version = parts[filesIdx + 3],
    )
}

/**
 * Maven local layout: `<repo-root>/<group-path>/<name>/<version>/<file>`
 * Group path uses '/' separators (e.g., `org/apache/lucene`).
 */
private fun parseMavenLocalCoordinates(file: File): ArtifactCoordinates? {
    val parts = file.absolutePath.replace('\\', '/').split('/')
    val repoIdx = parts.indexOfLast { it == "repository" }
    if (repoIdx < 0 || repoIdx + 3 >= parts.size) return null
    val version = parts[parts.size - 2]
    val name = parts[parts.size - 3]
    val groupParts = parts.subList(repoIdx + 1, parts.size - 3)
    if (groupParts.isEmpty()) return null
    return ArtifactCoordinates(
        group = groupParts.joinToString("."),
        name = name,
        version = version,
    )
}
