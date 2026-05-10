/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.tasks

import dev.nucleusframework.nucleus.desktop.application.internal.NativeLibArchDetector
import dev.nucleusframework.nucleus.desktop.application.internal.files.mangledName
import dev.nucleusframework.nucleus.desktop.tasks.AbstractNucleusTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Strips native libraries from dependency JARs when sandboxing is enabled.
 *
 * Since [AbstractExtractNativeLibsTask] already extracts native libs into the app resources
 * directory, keeping them inside the JARs would cause duplication in the final package.
 * This task rewrites JARs without native lib entries (.dylib, .jnilib, .so, .dll).
 *
 * Output JARs use content-hash-mangled filenames to avoid collisions when multiple
 * input JARs share the same simple name (common in multi-module projects).
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

        val expectedMainJarName = mainJarName.get()
        var strippedCount = 0

        for (file in inputJars.files) {
            if (!file.exists()) continue

            val outputFileName = file.mangledName()
            val outputFile = outDir.resolve(outputFileName)

            // Track the mangled name of the main JAR for downstream tasks
            if (file.name == expectedMainJarName) {
                outDir.resolve(MAIN_JAR_META_FILE).writeText(outputFileName)
            }

            if (!file.name.endsWith(".jar")) {
                file.copyTo(outputFile, overwrite = true)
                continue
            }

            // Quick check: does this JAR contain any native libs?
            var hasNativeLibs = false
            ZipInputStream(BufferedInputStream(file.inputStream())).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && NativeLibArchDetector.isNativeLib(entry.name)) {
                        hasNativeLibs = true
                        break
                    }
                    entry = zis.nextEntry
                }
            }

            if (!hasNativeLibs) {
                file.copyTo(outputFile, overwrite = true)
                continue
            }

            // Rewrite JAR without native lib entries
            ZipInputStream(BufferedInputStream(file.inputStream())).use { zis ->
                ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zos ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && NativeLibArchDetector.isNativeLib(entry.name)) {
                            logger.lifecycle(
                                "Sandboxing: stripped '{}' from {}",
                                entry.name,
                                file.name,
                            )
                            strippedCount++
                        } else {
                            zos.putNextEntry(
                                ZipEntry(entry.name).apply {
                                    time = entry.time
                                    if (entry.method == ZipEntry.STORED) {
                                        method = ZipEntry.STORED
                                        size = entry.size
                                        compressedSize = entry.compressedSize
                                        crc = entry.crc
                                    }
                                },
                            )
                            if (!entry.isDirectory) {
                                zis.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        }

        if (strippedCount > 0) {
            logger.lifecycle(
                "Sandboxing: stripped {} native lib(s) from JARs to avoid duplication with extracted resources",
                strippedCount,
            )
        }
    }

    private companion object {
        const val MAIN_JAR_META_FILE = ".main-jar-name"
    }
}
