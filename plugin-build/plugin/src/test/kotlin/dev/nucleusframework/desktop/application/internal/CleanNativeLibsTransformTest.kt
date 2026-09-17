/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector.NativeArch
import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector.NativeOs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strip decisions for `cleanupNativeLibs`, using the `com.squareup.zstd:zstd-kmp-jvm` layout
 * (arch dirs with no OS token) plus the OS+arch layouts of skiko and JNA.
 *
 * The rule the transform must never break: keep anything that can't be pinned down. Over-keeping
 * costs a few bytes in the JAR; over-removing costs an `UnsatisfiedLinkError` at runtime.
 */
class CleanNativeLibsTransformTest {
    @Suppress("MagicNumber")
    private fun macho(cpuType: Int): ByteArray =
        byteArrayOf(
            0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte(),
            (cpuType and 0xFF).toByte(),
            ((cpuType ushr 8) and 0xFF).toByte(),
            ((cpuType ushr 16) and 0xFF).toByte(),
            ((cpuType ushr 24) and 0xFF).toByte(),
        )

    @Suppress("MagicNumber")
    private fun elf(eMachine: Int): ByteArray {
        val buf = ByteArray(20)
        buf[0] = 0x7F
        buf[1] = 0x45
        buf[2] = 0x4C
        buf[3] = 0x46
        buf[4] = 2
        buf[5] = 1
        buf[18] = (eMachine and 0xFF).toByte()
        return buf
    }

    @Suppress("MagicNumber")
    private fun pe(machine: Int): ByteArray {
        val buf = ByteArray(256)
        buf[0] = 0x4D
        buf[1] = 0x5A
        buf[0x3C] = 0x78
        buf[0x7C] = (machine and 0xFF).toByte()
        buf[0x7D] = ((machine ushr 8) and 0xFF).toByte()
        return buf
    }

    @Suppress("MagicNumber")
    private val macArm64 = macho(0x0100000C)

    @Suppress("MagicNumber")
    private val macX64 = macho(0x01000007)

    private fun removed(
        path: String,
        os: NativeOs,
        arch: NativeArch,
        header: ByteArray = ByteArray(0),
    ) = shouldRemoveNativeLib(path, os, arch) { header }

    @Test
    fun `arch dirs without an OS token are stripped by arch on macOS`() {
        // The header alone says only "macOS"; the arch comes from the path. Before both were
        // merged, the opposite-arch dylib was kept for lack of a verdict.
        assertTrue(
            removed("jni/x86_64/libzstd-kmp.dylib", NativeOs.MACOS, NativeArch.ARM64, macX64),
        )
        assertFalse(
            removed("jni/aarch64/libzstd-kmp.dylib", NativeOs.MACOS, NativeArch.ARM64, macArm64),
        )
        assertTrue(
            removed("jni/aarch64/libzstd-kmp.dylib", NativeOs.MACOS, NativeArch.X64, macArm64),
        )
        assertFalse(
            removed("jni/x86_64/libzstd-kmp.dylib", NativeOs.MACOS, NativeArch.X64, macX64),
        )
    }

    @Test
    fun `same rule applies to windows and linux arch dirs`() {
        assertTrue(removed("jni/aarch64/zstd-kmp.dll", NativeOs.WINDOWS, NativeArch.X64, pe(0xAA64)))
        assertFalse(removed("jni/amd64/zstd-kmp.dll", NativeOs.WINDOWS, NativeArch.X64, pe(0x8664)))
        assertTrue(removed("jni/aarch64/libzstd-kmp.so", NativeOs.LINUX, NativeArch.X64, elf(0xB7)))
        assertFalse(removed("jni/amd64/libzstd-kmp.so", NativeOs.LINUX, NativeArch.X64, elf(0x3E)))
    }

    @Test
    fun `a flat dylib is filtered on its header alone`() {
        // No path token at all, so the verdict rests entirely on the Mach-O cpu_type — the field
        // that decoded to garbage while the magic was mapped to the wrong byte order.
        assertTrue(removed("libfoo.dylib", NativeOs.MACOS, NativeArch.X64, macArm64))
        assertFalse(removed("libfoo.dylib", NativeOs.MACOS, NativeArch.ARM64, macArm64))
        assertTrue(removed("libfoo.dylib", NativeOs.MACOS, NativeArch.ARM64, macX64))
        assertFalse(removed("libfoo.dylib", NativeOs.MACOS, NativeArch.X64, macX64))
    }

    @Test
    fun `libs for another OS are stripped whatever the layout`() {
        assertTrue(removed("jni/aarch64/zstd-kmp.dll", NativeOs.MACOS, NativeArch.ARM64, pe(0xAA64)))
        assertTrue(removed("jni/aarch64/libzstd-kmp.so", NativeOs.MACOS, NativeArch.ARM64, elf(0xB7)))
        assertTrue(removed("libskiko-macos-x64.dylib", NativeOs.MACOS, NativeArch.ARM64, macX64))
        assertTrue(removed("com/sun/jna/linux-x86-64/libjnidispatch.so", NativeOs.MACOS, NativeArch.ARM64))
    }

    @Test
    fun `libs matching the target are kept`() {
        assertFalse(removed("libskiko-macos-arm64.dylib", NativeOs.MACOS, NativeArch.ARM64, macArm64))
        assertFalse(removed("com/sun/jna/darwin-aarch64/libjnidispatch.jnilib", NativeOs.MACOS, NativeArch.ARM64))
        assertFalse(removed("nucleus/native/darwin-aarch64/libnucleus_tao.dylib", NativeOs.MACOS, NativeArch.ARM64))
    }

    @Test
    fun `undecidable entries are kept`() {
        // No usable path token and no recognisable magic → keep, never guess.
        assertFalse(removed("libmystery.dylib", NativeOs.MACOS, NativeArch.ARM64, byteArrayOf(1, 2, 3, 4)))
        assertFalse(removed("libmystery.dylib", NativeOs.MACOS, NativeArch.ARM64))
        // Universal binaries serve every arch.
        assertFalse(
            removed(
                "libuniversal.dylib",
                NativeOs.MACOS,
                NativeArch.ARM64,
                byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
            ),
        )
    }

    @Test
    fun `exotic platforms are always stripped`() {
        assertTrue(removed("native/freebsd-x86-64/libfoo.so", NativeOs.LINUX, NativeArch.X64))
        assertTrue(removed("native/linux-android/libfoo.so", NativeOs.LINUX, NativeArch.X64))
        assertTrue(removed("native/linux-ppc64le/libfoo.so", NativeOs.LINUX, NativeArch.X64))
    }
}
