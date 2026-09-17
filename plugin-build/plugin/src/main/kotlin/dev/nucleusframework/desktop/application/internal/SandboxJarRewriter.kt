/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.internal.files.withTimeOf
import org.gradle.api.logging.Logger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Rewrites a single dependency JAR for the sandbox pipeline (issue #317):
 * - native-lib entries → deterministic markers at the same path;
 * - `.class` entries → [SandboxBytecodeRewriter] rewrites `System.load`/`Runtime.load` call sites;
 * - other entries preserved (STORED entries keep their size/crc).
 *
 * Returns the `sha256(marker) -> bundledLibName` pairs to merge into the manifest.
 *
 * Extracted from [dev.nucleusframework.desktop.application.tasks.AbstractStripNativeLibsFromJarsTask]
 * so the jar-rewrite logic is unit-testable without a Gradle project.
 */
internal object SandboxJarRewriter {
    /** Result of rewriting a single JAR. */
    data class RewriteResult(
        /** `sha256(marker) -> bundledLibName` entries to merge into the manifest. */
        val manifest: Map<String, String>,
        /** Number of native-lib entries replaced with markers. */
        val markedLibs: Int,
        /** Number of `.class` entries whose `System.load`/`Runtime.load` call sites changed. */
        val rewrittenClasses: Int,
    )

    /**
     * @param inputJar the original dependency JAR
     * @param outputFile destination rewritten JAR
     * @param jarMangledName the mangled (content-hashed) name used as the marker's `jar=` tag
     * @param keepReal if true, copy the JAR verbatim (escape hatch) and return zeroed counts
     * @param logger optional, for lifecycle logs
     */
    fun rewriteJar(
        inputJar: File,
        outputFile: File,
        jarMangledName: String,
        keepReal: Boolean,
        logger: Logger? = null,
    ): RewriteResult {
        if (keepReal) {
            inputJar.copyTo(outputFile, overwrite = true)
            return RewriteResult(emptyMap(), 0, 0)
        }

        val manifest = LinkedHashMap<String, String>()
        var markedLibs = 0
        var rewrittenClasses = 0
        ZipInputStream(BufferedInputStream(inputJar.inputStream())).use { zis ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && NativeLibArchDetector.isNativeLib(entry.name)) {
                        val marker = SandboxMarkers.markerBytes(jarMangledName, entry.name)
                        val sha = SandboxMarkers.sha256Hex(marker)
                        val bundledName = SandboxMarkers.bundledLibName(entry.name)
                        // Last-write-wins is fine: duplicate filenames across JARs dedup at
                        // extraction time, and the bundled filename is identical for them.
                        manifest[sha] = bundledName
                        markedLibs++
                        zos.putNextEntry(ZipEntry(entry.name).withTimeOf(entry))
                        zos.write(marker)
                        zos.closeEntry()
                        logger?.lifecycle("Sandboxing: marked '{}' from {}", entry.name, inputJar.name)
                    } else if (!entry.isDirectory && entry.name.endsWith(".class") && entry.name != "module-info.class") {
                        val original = zis.readBytes()
                        val rewritten = SandboxBytecodeRewriter.rewriteSystemLoadCalls(original)
                        if (rewritten !== original) rewrittenClasses++
                        zos.putNextEntry(ZipEntry(entry.name).withTimeOf(entry))
                        zos.write(rewritten)
                        zos.closeEntry()
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
        return RewriteResult(manifest, markedLibs, rewrittenClasses)
    }

    /** Copies the embedded shim JAR resource into [outDir] under its fixed name. */
    fun injectShimJar(outDir: File): File {
        val resource =
            javaClass.getResourceAsStream(SandboxMarkers.SHIM_RESOURCE_PATH)
                ?: error(
                    "Sandbox shim JAR not found on the plugin classpath at " +
                        "${SandboxMarkers.SHIM_RESOURCE_PATH}. The plugin build must embed it as a resource.",
                )
        val dest = outDir.resolve(SandboxMarkers.SHIM_JAR_NAME)
        resource.use { input ->
            dest.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        }
        return dest
    }

    /** True if [file] (a JAR) contains any native-lib entries. */
    fun hasNativeLibs(file: File): Boolean {
        ZipInputStream(BufferedInputStream(file.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && NativeLibArchDetector.isNativeLib(entry.name)) return true
                entry = zis.nextEntry
            }
        }
        return false
    }

    /** Number of native-lib entries in [file] (a JAR). */
    fun countNativeLibs(file: File): Int {
        var count = 0
        ZipInputStream(BufferedInputStream(file.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && NativeLibArchDetector.isNativeLib(entry.name)) count++
                entry = zis.nextEntry
            }
        }
        return count
    }
}