package dev.nucleusframework.desktop.application.internal

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
 * Registers the project's own resources for inclusion in the GraalVM native image.
 *
 * native-image only embeds resources that are explicitly registered; statically we cannot
 * resolve dynamic `getResourceAsStream(path)` calls (paths coming from an index file, a
 * database, user input, …). To match the JVM distribution — where every resource ends up in
 * the uber JAR — this task walks the project's resource source directories (its own source
 * sets plus those of `project(...)` module dependencies) and emits one glob per top-level
 * entry: a directory is registered recursively (e.g. `commands` → all files under it), a file
 * keeps its own name (e.g. `tips.md`).
 *
 * The output is a standard `reachability-metadata.json`, passed to native-image via
 * `-H:ConfigurationFileDirectories=`.
 */
@CacheableTask
abstract class GenerateProjectResourceMetadataTask : DefaultTask() {
    /** The project's resource source directories (already resolved at configuration time). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourceDirs: ConfigurableFileCollection

    /** Output directory where reachability-metadata.json is written. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /**
     * Writes `reachability-metadata.json` to [outputDir] with one resource glob per top-level
     * entry of [resourceDirs], skipping hidden entries and `META-INF`.
     */
    @TaskAction
    fun generate() {
        val globs = sortedSetOf<String>()
        for (dir in resourceDirs.files) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.forEach { child ->
                val name = child.name
                // META-INF is handled by the auto-included native-image.properties globs
                // (META-INF/services/*) and may carry signatures/manifests we must not embed.
                if (name.startsWith(".") || name == "META-INF") return@forEach
                globs += if (child.isDirectory) "$name/**" else name
            }
        }

        val patterns = globs.map { ResourcePattern(glob = it) }.toSet()

        val outDir = outputDir.get().asFile
        outDir.mkdirs()
        File(outDir, "reachability-metadata.json")
            .writeText(buildReachabilityMetadataJson(emptySet(), emptySet(), patterns))

        logger.lifecycle(
            "Project resource metadata: registered ${patterns.size} resource glob(s) for native-image",
        )
    }
}
