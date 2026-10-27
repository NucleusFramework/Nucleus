/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import java.io.File

/**
 * Pure helpers for the plugin-side publishing of the merged auto-update manifest.
 *
 * Nucleus runs a separate electron-builder invocation per packaging format (TargetFormat).
 * Each run that produces an auto-updatable artifact also generates a single-artifact
 * `<channel><osSuffix>.yml` (Windows `<channel>.yml`, macOS `<channel>-mac.yml`, Linux
 * `<channel>-linux.yml`). Because the manifest name is keyed only on the OS, every format of the
 * same OS resolves to the **same** key — so publishing each per-format manifest independently makes
 * the last writer clobber the others, leaving the manifest with only one format. A client whose
 * artifact was dropped then finds no matching entry and the in-app updater throws
 * `NoMatchingFileException` (and on macOS, where Squirrel.Mac updates from the ZIP, a DMG-only
 * `latest-mac.yml` breaks updates entirely).
 *
 * The fix (see `AbstractMergeUpdateYmlTask` and the `publishAutoUpdate: false` it relies on) lets
 * electron-builder upload the artifacts but suppresses its per-format manifest upload; the plugin
 * then merges the per-format manifests with [UpdateYmlMerger] and uploads a single union manifest.
 * The functions here are the testable seams of that flow.
 */
internal object UpdateYmlPublish {
    /** Environment variable that overrides the publish mode (matches electron-builder's `--publish`). */
    const val PUBLISH_MODE_ENV = "NUCLEUS_PUBLISH_MODE"

    /**
     * CI environment variables that, when set to a non-blank value, indicate the current build was
     * triggered by a git tag. Mirrors electron-builder's own tag detection so the plugin only
     * uploads the merged manifest when electron-builder actually published the artifacts.
     */
    private val CI_TAG_ENV_VARS =
        listOf(
            "TRAVIS_TAG",
            "APPVEYOR_REPO_TAG_NAME",
            "CIRCLE_TAG",
            "BITRISE_GIT_TAG",
            "CI_COMMIT_TAG",
            "CI_BUILD_TAG",
        )

    /** A merged manifest plus the filename it should be written/uploaded under. */
    data class MergedManifest(val fileName: String, val content: String)

    /**
     * Resolves the electron-builder publish mode the same way
     * [AbstractElectronBuilderPackageTask][dev.nucleusframework.desktop.application.tasks.AbstractElectronBuilderPackageTask]
     * does: `never` when no provider is enabled, otherwise env var > Gradle property > DSL value.
     */
    fun resolvePublishFlag(
        anyProviderEnabled: Boolean,
        envValue: String?,
        propValue: String?,
        dslValue: String,
    ): String {
        if (!anyProviderEnabled) return "never"
        if (!envValue.isNullOrBlank()) return envValue
        if (!propValue.isNullOrBlank()) return propValue
        return dslValue
    }

    /**
     * Whether the plugin should upload the merged manifest, given the resolved [publishFlag] and the
     * CI environment.
     *
     * - `always` → always upload.
     * - `never` → never upload.
     * - anything else (i.e. `onTag`) → upload only when a CI tag is detected, matching when
     *   electron-builder itself publishes. When in doubt this returns `false` so the plugin never
     *   uploads a manifest pointing at artifacts electron-builder did not publish.
     */
    fun shouldPublish(
        publishFlag: String,
        ciEnv: Map<String, String?>,
    ): Boolean =
        when (publishFlag) {
            "always" -> true
            "never" -> false
            else -> hasCiTag(ciEnv)
        }

    /** Detects whether [ciEnv] indicates the build was triggered by a git tag. */
    fun hasCiTag(ciEnv: Map<String, String?>): Boolean {
        if (CI_TAG_ENV_VARS.any { !ciEnv[it].isNullOrBlank() }) return true
        val githubRef = ciEnv["GITHUB_REF"]
        return githubRef != null && githubRef.startsWith("refs/tags/")
    }

    /**
     * Builds the S3 object key for [fileName] under the optional [pathPrefix], matching how
     * electron-builder's S3 publisher lays files out (`<path>/<fileName>`, or just `<fileName>`
     * when no prefix is configured).
     */
    fun s3Key(
        pathPrefix: String?,
        fileName: String,
    ): String {
        val prefix = pathPrefix?.trim()?.trim('/').orEmpty()
        return if (prefix.isEmpty()) fileName else "$prefix/$fileName"
    }

    /**
     * Discovers the per-format `<channel><osSuffix>.yml` manifests electron-builder (or the plugin)
     * wrote into each of [outputDirs] and combines those that share a filename into one manifest each
     * (normally a single channel, e.g. `beta-mac.yml`).
     *
     * When only one format produced a given manifest it is returned **verbatim** (electron-builder's
     * exact bytes, preserving any fields the line-based merger does not model); only an actual
     * collision (two or more sources for the same filename) is re-serialized via [UpdateYmlMerger].
     *
     * Returns an empty list when no manifests are found (e.g. only non-updatable formats ran).
     */
    /** Deletes the update manifests in [outputDir], so a new packaging run cannot inherit stale ones. */
    fun deleteManifests(outputDir: File) {
        outputDir.listFiles()?.filter { it.isFile && UPDATE_YML_NAME.matches(it.name) }?.forEach(File::delete)
    }

    fun discoverAndMerge(outputDirs: List<File>): List<MergedManifest> {
        val byName = LinkedHashMap<String, MutableList<String>>()
        for (dir in outputDirs) {
            val ymls = dir.listFiles()?.filter { it.isFile && UPDATE_YML_NAME.matches(it.name) }.orEmpty()
            for (yml in ymls.sortedBy { it.name }) {
                byName.getOrPut(yml.name) { mutableListOf() }.add(yml.readText())
            }
        }
        return byName.map { (name, contents) ->
            val content = if (contents.size == 1) contents.single() else UpdateYmlMerger.merge(contents)
            MergedManifest(name, content)
        }
    }

    /**
     * Matches electron-builder update manifests across all platforms and channels, e.g. `latest.yml`
     * (Windows), `beta-mac.yml`, `alpha-linux.yml`. Anchored to the known channels so it never picks
     * up electron-builder's own config files (`electron-builder.yml`, `builder-debug.yml`).
     */
    private val UPDATE_YML_NAME = Regex("""^(latest|beta|alpha)(-mac|-linux)?\.yml$""")
}
