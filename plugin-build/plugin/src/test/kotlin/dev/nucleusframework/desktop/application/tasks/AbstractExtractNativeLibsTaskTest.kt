/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression coverage for issue #399: on a Windows x64 store build the extractor picked the ARM64
 * `zstd-kmp.dll` because its arch (present in the path) was discarded and the PE machine field sat
 * past the old 64-byte header buffer, reading back as UNKNOWN and slipping through the arch filter.
 *
 * Headers mirror the real `com.squareup.zstd:zstd-kmp-jvm` jar: arch dirs (`aarch64`, `amd64`,
 * `x86_64`) carry no OS token and mix `.dll`/`.so`/`.dylib`; the Windows `.dll`s use `e_lfanew`
 * 0x78, exactly as the shipped binaries do.
 */
class AbstractExtractNativeLibsTaskTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Suppress("MagicNumber")
    private fun pe(machine: Int): ByteArray {
        // Minimal PE: "MZ", e_lfanew (0x3C) = 0x78 (as in the real zstd dlls), machine at +4.
        val buf = ByteArray(256)
        buf[0] = 0x4D // 'M'
        buf[1] = 0x5A // 'Z'
        val peOffset = 0x78
        buf[0x3C] = (peOffset and 0xFF).toByte()
        buf[0x3D] = ((peOffset ushr 8) and 0xFF).toByte()
        buf[peOffset + 4] = (machine and 0xFF).toByte()
        buf[peOffset + 5] = ((machine ushr 8) and 0xFF).toByte()
        return buf
    }

    @Suppress("MagicNumber")
    private fun elf(eMachine: Int): ByteArray {
        val buf = ByteArray(64)
        buf[0] = 0x7F
        buf[1] = 0x45 // 'E'
        buf[2] = 0x4C // 'L'
        buf[3] = 0x46 // 'F'
        buf[4] = 2 // ELFCLASS64
        buf[5] = 1 // little-endian
        buf[18] = (eMachine and 0xFF).toByte()
        buf[19] = ((eMachine ushr 8) and 0xFF).toByte()
        return buf
    }

    @Suppress("MagicNumber")
    private fun macho(cpuType: Int): ByteArray {
        // Real thin Mach-O layout: the file is little-endian, so MH_MAGIC_64 is stored byte-swapped
        // as "cf fa ed fe" — the prefix every shipped dylib actually starts with — and cpu_type
        // follows in that same order.
        val buf = ByteArray(16)
        buf[0] = 0xCF.toByte()
        buf[1] = 0xFA.toByte()
        buf[2] = 0xED.toByte()
        buf[3] = 0xFE.toByte()
        buf[4] = (cpuType and 0xFF).toByte()
        buf[5] = ((cpuType ushr 8) and 0xFF).toByte()
        buf[6] = ((cpuType ushr 16) and 0xFF).toByte()
        buf[7] = ((cpuType ushr 24) and 0xFF).toByte()
        return buf
    }

    private val winArm64Dll = pe(0xAA64)
    private val winX64Dll = pe(0x8664)
    private val macArm64Dylib = macho(0x0100000C)
    private val macX64Dylib = macho(0x01000007)

    /** Entry set mirroring zstd-kmp-jvm 0.4.0 exactly. */
    private fun buildInputJar(file: File): File {
        val entries =
            linkedMapOf(
                "jni/aarch64/libzstd-kmp.dylib" to macArm64Dylib, // macOS arm64
                "jni/aarch64/libzstd-kmp.so" to elf(0xB7), // linux arm64
                "jni/aarch64/zstd-kmp.dll" to winArm64Dll, // windows arm64
                "jni/amd64/libzstd-kmp.so" to elf(0x3E), // linux x64
                "jni/amd64/zstd-kmp.dll" to winX64Dll, // windows x64
                "jni/x86_64/libzstd-kmp.dylib" to macX64Dylib, // macOS x64
            )
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return file
    }

    private fun runExtract(os: String, arch: String): File {
        val jar = buildInputJar(tmp.newFile("zstd-kmp-jvm-0.4.0.jar"))
        val outDir = tmp.newFolder("out")
        val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
        val task =
            project.tasks.register("extractNatives", AbstractExtractNativeLibsTask::class.java) {
                it.inputJars.setFrom(jar)
                it.targetOs.set(os)
                it.targetArch.set(arch)
                it.outputDir.set(outDir)
            }.get()
        task.extract()
        return outDir
    }

    @Test
    fun `windows x64 target extracts the x64 dll, not the arm64 one with the same name`() {
        val outDir = runExtract(os = "windows", arch = "x64")

        val extracted = outDir.resolve("zstd-kmp.dll")
        assertTrue("expected the flattened dll to be extracted", extracted.isFile)

        val bytes = extracted.readBytes()
        assertArrayEquals("wrong-arch dll was extracted for an x64 target (issue #399)", winX64Dll, bytes)

        val info = NativeLibArchDetector.detectFromHeader(bytes)
        assertEquals(NativeLibArchDetector.NativeOs.WINDOWS, info.os)
        assertEquals(NativeLibArchDetector.NativeArch.X64, info.arch)

        // Only the Windows dll belongs on a Windows target — no cross-OS siblings leak through.
        assertFalse(outDir.resolve("libzstd-kmp.so").exists())
        assertFalse(outDir.resolve("libzstd-kmp.dylib").exists())
    }

    @Test
    fun `windows arm64 target extracts the arm64 dll`() {
        val outDir = runExtract(os = "windows", arch = "arm64")

        val extracted = outDir.resolve("zstd-kmp.dll")
        assertTrue(extracted.isFile)
        assertArrayEquals(winArm64Dll, extracted.readBytes())
    }

    /**
     * The two dylibs flatten to the same `libzstd-kmp.dylib`, so exactly one may survive the
     * filter. Before the arch merge both did — they were `(MACOS, UNKNOWN)` — and the winner was
     * whichever came first in the JAR, i.e. the arm64 one on an Intel target.
     */
    @Test
    fun `macos x64 target extracts the x64 dylib, not the arm64 one listed before it`() {
        val outDir = runExtract(os = "macos", arch = "x64")

        val extracted = outDir.resolve("libzstd-kmp.dylib")
        assertTrue("expected the flattened dylib to be extracted", extracted.isFile)
        assertArrayEquals("wrong-arch dylib was extracted for an x64 target", macX64Dylib, extracted.readBytes())

        assertFalse(outDir.resolve("zstd-kmp.dll").exists())
        assertFalse(outDir.resolve("libzstd-kmp.so").exists())
    }

    @Test
    fun `macos arm64 target extracts the arm64 dylib`() {
        val outDir = runExtract(os = "macos", arch = "arm64")

        val extracted = outDir.resolve("libzstd-kmp.dylib")
        assertTrue(extracted.isFile)
        assertArrayEquals(macArm64Dylib, extracted.readBytes())
    }

    @Test
    fun `linux x64 target extracts only the x64 so`() {
        val outDir = runExtract(os = "linux", arch = "x64")

        val extracted = outDir.resolve("libzstd-kmp.so")
        assertTrue(extracted.isFile)
        assertArrayEquals(elf(0x3E), extracted.readBytes())
        assertFalse(outDir.resolve("zstd-kmp.dll").exists())
        assertFalse(outDir.resolve("libzstd-kmp.dylib").exists())
    }
}
