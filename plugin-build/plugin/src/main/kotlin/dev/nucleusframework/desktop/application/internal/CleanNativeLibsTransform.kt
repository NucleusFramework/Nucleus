/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector.NativeArch
import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector.NativeInfo
import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector.NativeOs
import dev.nucleusframework.internal.utils.Arch
import dev.nucleusframework.internal.utils.OS
import dev.nucleusframework.internal.utils.currentArch
import dev.nucleusframework.internal.utils.currentOS
import org.gradle.api.Project
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@DisableCachingByDefault(because = "Stripping native libs from JARs is fast and not worth caching")
internal abstract class CleanNativeLibsTransform : TransformAction<CleanNativeLibsTransform.Parameters> {
    interface Parameters : TransformParameters {
        @get:org.gradle.api.tasks.Input
        val targetOs: Property<String>

        @get:org.gradle.api.tasks.Input
        val targetArch: Property<String>
    }

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val inputFile = inputArtifact.get().asFile
        if (!inputFile.name.endsWith(".jar")) {
            outputs.file(inputFile)
            return
        }

        val targetOs = parameters.targetOs.get()
        val targetArch = parameters.targetArch.get()
        val expectedOs =
            mapOs(targetOs) ?: run {
                outputs.file(inputFile)
                return
            }
        val expectedArch =
            mapArch(targetArch) ?: run {
                outputs.file(inputFile)
                return
            }

        // First pass: determine which entries to remove
        val entriesToRemove = mutableSetOf<String>()
        ZipInputStream(BufferedInputStream(inputFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && NativeLibArchDetector.isNativeLib(entry.name)) {
                    // Only process files with native extensions (.dll, .so, .dylib, .jnilib)
                    // Never touch .class or other Java files
                    val pathInfo = NativeLibArchDetector.detectFromPath(entry.name)

                    if (pathInfo.os != NativeOs.UNKNOWN && pathInfo.arch != NativeArch.UNKNOWN) {
                        // Path has both OS and arch indicators
                        if (shouldRemoveByInfo(pathInfo, expectedOs, expectedArch)) {
                            entriesToRemove.add(entry.name)
                        }
                    } else {
                        // Fall back to binary header detection
                        val header = ByteArray(64)
                        val bytesRead = readFully(zis, header)
                        if (bytesRead > 0) {
                            val headerInfo = NativeLibArchDetector.detectFromHeader(header.copyOf(bytesRead))
                            if (shouldRemoveByInfo(headerInfo, expectedOs, expectedArch)) {
                                entriesToRemove.add(entry.name)
                            }
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }

        if (entriesToRemove.isEmpty()) {
            outputs.file(inputFile)
            return
        }

        // Second pass: copy JAR without removed entries
        val outputFile = outputs.file(inputFile.name)
        ZipInputStream(BufferedInputStream(inputFile.inputStream())).use { zis ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val shouldSkip = !entry.isDirectory && entry.name in entriesToRemove

                    if (!shouldSkip) {
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

    private fun shouldRemoveByInfo(
        info: NativeInfo,
        expectedOs: NativeOs,
        expectedArch: NativeArch,
    ): Boolean {
        // Exotic/unsupported OS (freebsd, aix, android, etc.) → always remove
        if (info.os == NativeOs.OTHER) return true
        // Known OS that doesn't match target → remove
        if (info.os != NativeOs.UNKNOWN && info.os != expectedOs) return true
        // OS matches but exotic/unsupported arch (ppc, arm32, riscv, etc.) → remove
        if (info.arch == NativeArch.OTHER) return true
        // OS matches but arch doesn't → remove (unless arch is unknown/universal)
        if (info.os == expectedOs &&
            info.arch != NativeArch.UNKNOWN &&
            info.arch != NativeArch.UNIVERSAL &&
            info.arch != expectedArch
        ) {
            return true
        }
        return false
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

    private fun readFully(
        input: java.io.InputStream,
        buffer: ByteArray,
    ): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return totalRead
    }
}

private val NATIVE_LIBS_CLEANED = Attribute.of("native-libs-cleaned", Boolean::class.javaObjectType)

internal fun registerCleanNativeLibsTransform(project: Project) {
    val osName =
        when (currentOS) {
            OS.Windows -> "windows"
            OS.Linux -> "linux"
            OS.MacOS -> "macos"
        }
    val archName =
        when (currentArch) {
            Arch.X64 -> "x64"
            Arch.Arm64 -> "arm64"
        }

    project.dependencies.registerTransform(CleanNativeLibsTransform::class.java) { spec ->
        spec.from.attribute(NATIVE_LIBS_CLEANED, false)
        spec.to.attribute(NATIVE_LIBS_CLEANED, true)
        spec.parameters.targetOs.set(osName)
        spec.parameters.targetArch.set(archName)
    }

    // Kotlin Multiplatform desktop runtime classpaths resolve project dependencies to
    // their `classes` + `resources` directory sub-variants (unlike plain JVM, which
    // resolves to the jar variant). Those directories carry no `native-libs-cleaned`
    // attribute, so requiring it makes artifact selection ambiguous, and the JAR-based
    // transform can't clean directories anyway. Requesting the `jar` LibraryElements
    // forces KMP project deps to resolve to their jar variant, restoring the same
    // resolution as plain JVM so the cleanup transform applies uniformly.
    val isMultiplatform = project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")
    val jarLibraryElements =
        project.objects.named(LibraryElements::class.java, LibraryElements.JAR)

    project.configurations.configureEach { configuration ->
        val name = configuration.name
        if (name.endsWith("RuntimeClasspath", ignoreCase = true) && !name.contains("Test", ignoreCase = true)) {
            // Skip Android configurations to avoid breaking Android builds.
            // Android's dexing transforms produce directories, not JARs, so applying
            // our JAR-based transform to Android configurations causes failures.
            val isAndroid = configuration.attributes.keySet().any { it.name.startsWith("com.android") }
            // Skip Compose Hot Reload dev classpath: its custom usage type
            // (compose-dev-java-runtime) consumes project variants that can't be
            // disambiguated when an extra attribute is added. Hot Reload runs the
            // app in-place and never packages, so native-lib cleanup is a no-op.
            val isHotReload = name.contains("HotReload", ignoreCase = true)
            if (!isAndroid && !isHotReload) {
                configuration.attributes.attribute(NATIVE_LIBS_CLEANED, true)
                if (isMultiplatform) {
                    configuration.attributes.attribute(
                        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        jarLibraryElements,
                    )
                }
            }
        }
    }

    project.dependencies.artifactTypes.configureEach { artifactType ->
        if (artifactType.name == "jar") {
            artifactType.attributes.attribute(NATIVE_LIBS_CLEANED, false)
        }
    }
}
