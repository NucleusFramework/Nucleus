/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.internal.SandboxJarRewriter
import dev.nucleusframework.desktop.application.internal.SandboxMarkers
import dev.nucleusframework.desktop.application.internal.files.mangledName
import dev.nucleusframework.desktop.tasks.AbstractNucleusTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.util.Properties

/**
 * Strips native libraries from dependency JARs when sandboxing is enabled and rewrites the
 * extract-and-load call sites that would otherwise break in store distributions (issue #317).
 *
 * For each native-lib entry the task writes a small unique **marker** file at the same resource
 * path (instead of bare stripping) and records `sha256(marker) -> bundled lib filename` in a
 * manifest. It rewrites every `System.load(String)` / `Runtime.load(String)` call site in
 * dependency classes to route through the runtime shim
 * `dev.nucleusframework.sandbox.NucleusSandboxLoader.load`, and injects the shim JAR onto the app
 * classpath. At runtime a third-party loader extracts the marker to temp and calls `System.load`;
 * the shim hashes it, looks the bundled signed copy up in the manifest, and loads that instead.
 * `System.loadLibrary`-first loaders are intentionally untouched (they already work via
 * `java.library.path`). Reflection-based `System.load` calls escape rewriting (documented gap).
 *
 * JARs matching [keepNativeLibsInJar] are copied verbatim (real native libs kept, no rewrite) —
 * an escape hatch for loaders that validate the extracted content (magic bytes/checksums) and
 * would reject a marker.
 *
 * The jar-rewrite logic lives in [SandboxJarRewriter] (unit-tested); this task wires Gradle
 * inputs/outputs and emits the manifest. Output JARs use content-hash-mangled filenames
 * ([mangledName]) to avoid collisions when multiple input JARs share the same simple name.
 */
@DisableCachingByDefault(because = "Rewrites JARs to strip native libs; fast and not worth caching")
abstract class AbstractStripNativeLibsFromJarsTask : AbstractNucleusTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputJars: ConfigurableFileCollection

    @get:Input
    abstract val mainJarName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Receives `nucleus-sandbox-manifest.properties` (`sha256(marker)=bundledLibName`). */
    @get:OutputDirectory
    abstract val manifestOutputDir: DirectoryProperty

    /**
     * Substrings matched against input JAR simple names. A JAR whose name contains any entry here
     * is copied verbatim (native libs kept, classes not rewritten) — escape hatch for loaders that
     * validate extracted content and would reject a marker.
     */
    @get:Input
    abstract val keepNativeLibsInJar: SetProperty<String>

    @get:Internal
    val mainJarInOutputDir: Provider<RegularFile>
        get() =
            outputDir.map { dir ->
                val metaFile = dir.asFile.resolve(MAIN_JAR_META_FILE)
                val mangledName = if (metaFile.exists()) metaFile.readText().trim() else mainJarName.get()
                dir.file(mangledName)
            }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
    @TaskAction
    fun strip() {
        val outDir = outputDir.get().asFile
        if (outDir.exists()) outDir.deleteRecursively()
        outDir.mkdirs()

        val manifestDir = manifestOutputDir.get().asFile
        if (manifestDir.exists()) manifestDir.deleteRecursively()
        manifestDir.mkdirs()

        val manifest = Properties()
        val escapeMatchers = keepNativeLibsInJar.get()
        val expectedMainJarName = mainJarName.get()
        var markedCount = 0
        var keptCount = 0
        var rewrittenClassCount = 0

        // Inject the runtime shim JAR onto the app classpath (fixed name, not mangled).
        SandboxJarRewriter.injectShimJar(outDir)
        // The output is a directory, which loses the input order: record it for the package task.
        val classpathOrder = mutableListOf(SandboxMarkers.SHIM_JAR_NAME)
        logger.lifecycle("Sandboxing: injected runtime shim JAR '{}'", SandboxMarkers.SHIM_JAR_NAME)

        for (file in inputJars.files) {
            if (!file.exists()) continue

            val outputFileName = file.mangledName()
            val outputFile = outDir.resolve(outputFileName)
            classpathOrder += outputFileName

            // Track the mangled name of the main JAR for downstream tasks
            if (file.name == expectedMainJarName) {
                outDir.resolve(MAIN_JAR_META_FILE).writeText(outputFileName)
            }

            if (!file.name.endsWith(".jar")) {
                file.copyTo(outputFile, overwrite = true)
                continue
            }

            val keepReal = escapeMatchers.any { file.name.contains(it) }
            if (keepReal) {
                file.copyTo(outputFile, overwrite = true)
                keptCount += SandboxJarRewriter.countNativeLibs(file)
                logger.lifecycle("Sandboxing: kept native libs verbatim in {} (escape hatch)", file.name)
                continue
            }

            if (!SandboxJarRewriter.hasNativeLibs(file)) {
                // No native libs to mark — but still rewrite System.load call sites in classes,
                // which is cheap and keeps the shim passthrough consistent across the classpath.
            }

            val result = SandboxJarRewriter.rewriteJar(file, outputFile, outputFileName, keepReal = false, logger)
            for ((sha, bundledName) in result.manifest) {
                manifest.setProperty(sha, bundledName)
            }
            markedCount += result.markedLibs
            rewrittenClassCount += result.rewrittenClasses
        }

        outDir.resolve(CLASSPATH_ORDER_FILE).writeText(classpathOrder.joinToString("\n", postfix = "\n"))

        // Emit the manifest next to the extracted native libs (packaged into app resources).
        val manifestFile = manifestDir.resolve(SandboxMarkers.MANIFEST_FILENAME)
        manifest.store(
            manifestFile.outputStream().buffered(),
            "Nucleus sandbox native-lib redirect manifest: sha256(marker)=bundledLibName",
        )

        logger.lifecycle(
            "Sandboxing: marked {} native lib(s), rewrote {} class(es), kept {} lib(s) verbatim; " +
                "manifest at {}",
            markedCount,
            rewrittenClassCount,
            keptCount,
            manifestFile,
        )
    }

    private companion object {
        const val MAIN_JAR_META_FILE = ".main-jar-name"
        const val CLASSPATH_ORDER_FILE = ".classpath-order"
    }
}