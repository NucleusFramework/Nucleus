@file:Suppress("ktlint:standard:filename", "NestedBlockDepth", "TooGenericExceptionCaught")

package dev.nucleusframework.nucleus.desktop.application.internal

import groovy.json.JsonSlurper
import org.gradle.api.logging.Logger
import java.io.File
import java.util.zip.ZipFile

/**
 * Resolves GraalVM reachability metadata from the Oracle repository for runtime dependencies.
 *
 * Extracts the pre-downloaded metadata repository ZIP, then for each runtime dependency
 * looks up matching metadata in the repository's index.json files and returns the list of
 * directories that should be passed as `-H:ConfigurationFileDirectories=` to native-image.
 *
 * @see <a href="https://github.com/oracle/graalvm-reachability-metadata">oracle/graalvm-reachability-metadata</a>
 */
internal fun resolveMetadataRepositoryFromArtifacts(
    zipFile: File,
    version: String,
    excludedModules: Set<String>,
    moduleToConfigVersion: Map<String, String>,
    artifacts: List<ArtifactCoordinates>,
    outputDir: File,
    logger: Logger,
): List<File> {
    val repoDir =
        extractRepository(zipFile, version, outputDir, logger)
            ?: return emptyList()

    val resolvedDirs = mutableListOf<File>()

    for (artifact in artifacts) {
        val coordinates = "${artifact.group}:${artifact.name}"

        if (coordinates in excludedModules) {
            logger.debug("Skipping excluded module: $coordinates")
            continue
        }

        val metadataDir =
            findMetadataForDependency(
                repoDir = repoDir,
                group = artifact.group,
                name = artifact.name,
                version = moduleToConfigVersion[coordinates] ?: artifact.version,
                logger = logger,
            )

        if (metadataDir != null) {
            resolvedDirs.add(metadataDir)
            logger.info("Resolved metadata repository config for $coordinates -> $metadataDir")
        }
    }

    return resolvedDirs
}

/**
 * Extracts the metadata repository ZIP to [outputDir].
 * Returns the root directory of the extracted repository, or null on failure.
 */
private fun extractRepository(
    zipFile: File,
    version: String,
    outputDir: File,
    logger: Logger,
): File? {
    val extractedMarker = File(outputDir, ".extracted-$version")

    // Skip if already extracted for this version
    if (extractedMarker.exists()) {
        logger.debug("Metadata repository v$version already extracted at $outputDir")
        return outputDir
    }

    // Clean previous extraction
    if (outputDir.exists()) outputDir.deleteRecursively()
    outputDir.mkdirs()

    try {
        ZipFile(zipFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val targetFile = File(outputDir, entry.name)
                targetFile.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    } catch (e: Exception) {
        logger.warn("Failed to extract metadata repository ZIP: ${e.message}")
        return null
    }

    extractedMarker.writeText(version)
    return outputDir
}

/**
 * Finds metadata directory for a specific dependency in the extracted repository.
 *
 * Repository structure:
 * ```
 * <groupId>/
 *   <artifactId>/
 *     index.json
 *     <version>/
 *       reflect-config.json, jni-config.json, resource-config.json, etc.
 * ```
 */
private fun findMetadataForDependency(
    repoDir: File,
    group: String,
    name: String,
    version: String,
    logger: Logger,
): File? {
    val moduleDir = File(repoDir, "$group/$name")
    val indexFile = File(moduleDir, "index.json")

    if (!indexFile.exists()) return null

    val slurper = JsonSlurper()

    @Suppress("UNCHECKED_CAST")
    val indexEntries =
        try {
            slurper.parseText(indexFile.readText()) as? List<Map<String, Any?>>
        } catch (e: Exception) {
            logger.debug("Failed to parse index.json for $group:$name: ${e.message}")
            return null
        } ?: return null

    val metadataVersion =
        findMatchingMetadataVersion(indexEntries, version)
            ?: return null

    val metadataDir = File(moduleDir, metadataVersion)
    return if (metadataDir.exists() && metadataDir.isDirectory) metadataDir else null
}

/**
 * Finds the metadata version directory that matches the given dependency version.
 *
 * Each index.json entry has:
 * - "latest": boolean
 * - "metadata-version": string — subdirectory name
 * - "tested-versions": list<string>
 */
@Suppress("UNCHECKED_CAST")
private fun findMatchingMetadataVersion(
    indexEntries: List<Map<String, Any?>>,
    version: String,
): String? {
    // First, look for an exact match in tested-versions
    for (entry in indexEntries) {
        val testedVersions = entry["tested-versions"] as? List<String> ?: continue
        if (version in testedVersions) {
            return entry["metadata-version"] as? String
        }
    }

    // Fall back to the "latest" entry
    for (entry in indexEntries) {
        if (entry["latest"] == true) {
            return entry["metadata-version"] as? String
        }
    }

    // Last resort: use the first entry
    return indexEntries.firstOrNull()?.get("metadata-version") as? String
}
