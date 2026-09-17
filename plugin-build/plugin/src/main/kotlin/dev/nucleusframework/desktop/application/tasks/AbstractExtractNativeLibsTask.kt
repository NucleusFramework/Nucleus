/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector
import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector.NativeArch
import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector.NativeOs
import dev.nucleusframework.desktop.tasks.AbstractNucleusTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream

/**
 * Extracts native libraries from dependency JARs and places them in an output directory
 * so they can be included in app resources (and signed automatically by the packaging plugin).
 *
 * This is required for macOS App Sandbox compatibility: sandboxed apps cannot load
 * unsigned native code extracted to temp directories at runtime.
 */
@DisableCachingByDefault(because = "Extracts native libs from JARs; output depends on JAR content order")
abstract class AbstractExtractNativeLibsTask : AbstractNucleusTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputJars: ConfigurableFileCollection

    @get:Input
    abstract val targetOs: Property<String>

    @get:Input
    abstract val targetArch: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @Suppress("NestedBlockDepth")
    @TaskAction
    fun extract() {
        val outDir = outputDir.get().asFile
        if (outDir.exists()) {
            outDir.deleteRecursively()
        }
        outDir.mkdirs()

        val expectedOs = mapOs(targetOs.get()) ?: return
        val expectedArch = mapArch(targetArch.get()) ?: return

        val extractedFiles = mutableSetOf<String>()

        for (jarFile in inputJars.files) {
            if (!jarFile.name.endsWith(".jar") || !jarFile.exists()) continue

            ZipInputStream(BufferedInputStream(jarFile.inputStream())).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && NativeLibArchDetector.isNativeLib(entry.name)) {
                        val info = detectInfo(entry.name, zis)
                        if (shouldExtract(info, expectedOs, expectedArch)) {
                            val fileName = entry.name.substringAfterLast('/')
                            // Always flatten to the output root. We already filter by
                            // target OS/arch so there are no naming conflicts, and
                            // flattening is required for:
                            //  1. macOS universal builds – lipo needs the same relative
                            //     path in both the arm64 and x64 .app bundles.
                            //  2. JNA boot library loading – jna.boot.library.path
                            //     looks directly in the listed directories.
                            //  3. Simplicity – a single java.library.path entry is
                            //     enough for all native libs.
                            val relativePath = fileName
                            if (fileName in extractedFiles) {
                                logger.warn(
                                    "Sandboxing: skipping duplicate native lib" +
                                        " '{}' from {} (already extracted from another JAR)",
                                    fileName,
                                    jarFile.name,
                                )
                            } else {
                                val destFile = outDir.resolve(relativePath)
                                destFile.parentFile.mkdirs()
                                // Re-open the JAR to read the entry bytes
                                // (we may have consumed some for header detection)
                                extractEntry(jarFile, entry.name, destFile)
                                extractedFiles.add(fileName)
                                logger.lifecycle("Sandboxing: extracted '{}' from {}", relativePath, jarFile.name)
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }

        if (extractedFiles.isEmpty()) {
            logger.lifecycle("Sandboxing: no native libraries found to extract from dependency JARs")
        } else {
            logger.lifecycle("Sandboxing: extracted {} native lib(s) to {}", extractedFiles.size, outDir)
        }
    }

    private fun detectInfo(
        entryName: String,
        zis: ZipInputStream,
    ): NativeLibArchDetector.NativeInfo =
        NativeLibArchDetector.detectEntry(entryName) { NativeLibArchDetector.readHeaderBytes(zis) }

    private fun shouldExtract(
        info: NativeLibArchDetector.NativeInfo,
        expectedOs: NativeOs,
        expectedArch: NativeArch,
    ): Boolean {
        // Must match target OS
        if (info.os != expectedOs && info.os != NativeOs.UNKNOWN) return false
        // Must match target arch (or be universal/unknown)
        if (info.arch != expectedArch &&
            info.arch != NativeArch.UNIVERSAL &&
            info.arch != NativeArch.UNKNOWN
        ) {
            return false
        }
        // Reject exotic OS/arch
        if (info.os == NativeOs.OTHER || info.arch == NativeArch.OTHER) return false
        // If both OS and arch are unknown, we can't determine — skip to be safe
        if (info.os == NativeOs.UNKNOWN && info.arch == NativeArch.UNKNOWN) return false
        return true
    }

    @Suppress("NestedBlockDepth")
    private fun extractEntry(
        jarFile: java.io.File,
        entryName: String,
        destFile: java.io.File,
    ) {
        ZipInputStream(BufferedInputStream(jarFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == entryName) {
                    destFile.outputStream().use { out ->
                        zis.copyTo(out)
                    }
                    return
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun mapOs(os: String): NativeOs? =
        when (os) {
            "windows" -> NativeOs.WINDOWS
            "linux" -> NativeOs.LINUX
            "macos" -> NativeOs.MACOS
            else -> null
        }

    private fun mapArch(arch: String): NativeArch? =
        when (arch) {
            "x64" -> NativeArch.X64
            "arm64" -> NativeArch.ARM64
            else -> null
        }

}
