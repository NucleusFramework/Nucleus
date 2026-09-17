/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector.NativeArch
import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector.NativeInfo
import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector.NativeOs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-order coverage for [NativeLibArchDetector.detectFromHeader] and merge semantics for
 * [NativeLibArchDetector.detectEntry].
 *
 * The Mach-O cases matter because every macOS `.dylib` on disk is little-endian and therefore
 * starts with the byte-swapped magic `cf fa ed fe`. Mapping that magic to a big-endian read (as
 * this detector used to) makes `cpu_type` decode to `0x07000001` instead of `0x01000007`, so real
 * dylibs reported `arch=UNKNOWN` and no packaging filter could tell arm64 from x86_64.
 */
class NativeLibArchDetectorTest {
    @Suppress("MagicNumber")
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    // --- Mach-O ---

    @Test
    fun `little-endian 64-bit mach-o reports its cpu type`() {
        // "cf fa ed fe" + cpu_type little-endian — the exact prefix of every shipped arm64/x64 dylib.
        assertEquals(
            NativeInfo(NativeOs.MACOS, NativeArch.ARM64),
            NativeLibArchDetector.detectFromHeader(bytes(0xCF, 0xFA, 0xED, 0xFE, 0x0C, 0x00, 0x00, 0x01)),
        )
        assertEquals(
            NativeInfo(NativeOs.MACOS, NativeArch.X64),
            NativeLibArchDetector.detectFromHeader(bytes(0xCF, 0xFA, 0xED, 0xFE, 0x07, 0x00, 0x00, 0x01)),
        )
    }

    @Test
    fun `big-endian 64-bit mach-o is read in big-endian order`() {
        // Magic stored unswapped means the whole header is big-endian, cpu_type included.
        assertEquals(
            NativeInfo(NativeOs.MACOS, NativeArch.X64),
            NativeLibArchDetector.detectFromHeader(bytes(0xFE, 0xED, 0xFA, 0xCF, 0x01, 0x00, 0x00, 0x07)),
        )
    }

    @Test
    fun `32-bit mach-o follows the same byte-order rule`() {
        assertEquals(
            NativeInfo(NativeOs.MACOS, NativeArch.ARM64),
            NativeLibArchDetector.detectFromHeader(bytes(0xCE, 0xFA, 0xED, 0xFE, 0x0C, 0x00, 0x00, 0x01)),
        )
        assertEquals(
            NativeInfo(NativeOs.MACOS, NativeArch.ARM64),
            NativeLibArchDetector.detectFromHeader(bytes(0xFE, 0xED, 0xFA, 0xCE, 0x01, 0x00, 0x00, 0x0C)),
        )
    }

    @Test
    fun `fat binaries report UNIVERSAL in both 32- and 64-bit flavours`() {
        // Fat headers are always big-endian; 0xCAFEBABF is the 64-bit variant.
        assertEquals(
            NativeInfo(NativeOs.MACOS, NativeArch.UNIVERSAL),
            NativeLibArchDetector.detectFromHeader(bytes(0xCA, 0xFE, 0xBA, 0xBE, 0x00, 0x00, 0x00, 0x02)),
        )
        assertEquals(
            NativeInfo(NativeOs.MACOS, NativeArch.UNIVERSAL),
            NativeLibArchDetector.detectFromHeader(bytes(0xCA, 0xFE, 0xBA, 0xBF, 0x00, 0x00, 0x00, 0x02)),
        )
    }

    @Test
    fun `unknown cpu type still resolves the OS`() {
        assertEquals(
            NativeInfo(NativeOs.MACOS, NativeArch.UNKNOWN),
            NativeLibArchDetector.detectFromHeader(bytes(0xCF, 0xFA, 0xED, 0xFE, 0x12, 0x00, 0x00, 0x00)),
        )
    }

    // --- PE / ELF ---

    @Test
    fun `PE machine field is reachable past the first 64 bytes`() {
        // e_lfanew = 0x78 (as in the shipped zstd-kmp dlls) puts the machine field at byte 124.
        val pe = ByteArray(256)
        pe[0] = 0x4D
        pe[1] = 0x5A
        pe[0x3C] = 0x78
        pe[0x7C] = 0x64
        pe[0x7D] = 0x86.toByte()
        assertEquals(NativeInfo(NativeOs.WINDOWS, NativeArch.X64), NativeLibArchDetector.detectFromHeader(pe))
        // Truncated to the old buffer size the machine field is simply out of reach.
        assertEquals(
            NativeInfo(NativeOs.WINDOWS, NativeArch.UNKNOWN),
            NativeLibArchDetector.detectFromHeader(pe.copyOf(64)),
        )
    }

    @Test
    fun `ELF machine field is decoded per its endianness flag`() {
        val elf = ByteArray(64)
        elf[0] = 0x7F
        elf[1] = 0x45
        elf[2] = 0x4C
        elf[3] = 0x46
        elf[4] = 2
        elf[5] = 1 // little-endian
        elf[18] = 0xB7.toByte()
        assertEquals(NativeInfo(NativeOs.LINUX, NativeArch.ARM64), NativeLibArchDetector.detectFromHeader(elf))

        elf[5] = 2 // big-endian
        elf[18] = 0x00
        elf[19] = 0x3E
        assertEquals(NativeInfo(NativeOs.LINUX, NativeArch.X64), NativeLibArchDetector.detectFromHeader(elf))
    }

    // --- detectEntry: path + header merge ---

    @Test
    fun `path arch and header OS complement each other`() {
        // zstd-kmp's layout: the arch is in the path, the OS only in the binary.
        val info =
            NativeLibArchDetector.detectEntry("jni/aarch64/libzstd-kmp.dylib") {
                bytes(0xCF, 0xFA, 0xED, 0xFE, 0x0C, 0x00, 0x00, 0x01)
            }
        assertEquals(NativeInfo(NativeOs.MACOS, NativeArch.ARM64), info)
    }

    @Test
    fun `path wins over a disagreeing header`() {
        val info =
            NativeLibArchDetector.detectEntry("jni/x86_64/libzstd-kmp.dylib") {
                bytes(0xCF, 0xFA, 0xED, 0xFE, 0x0C, 0x00, 0x00, 0x01) // says arm64
            }
        assertEquals(NativeInfo(NativeOs.MACOS, NativeArch.X64), info)
    }

    @Test
    fun `a fully resolved path never reads the header`() {
        var read = false
        val info =
            NativeLibArchDetector.detectEntry("com/sun/jna/darwin-aarch64/libjnidispatch.jnilib") {
                read = true
                ByteArray(0)
            }
        assertFalse("header must not be read when the path resolves both fields", read)
        assertEquals(NativeInfo(NativeOs.MACOS, NativeArch.ARM64), info)
    }

    @Test
    fun `an unreadable header leaves the path result untouched`() {
        val info = NativeLibArchDetector.detectEntry("jni/aarch64/libzstd-kmp.dylib") { ByteArray(0) }
        assertEquals(NativeInfo(NativeOs.UNKNOWN, NativeArch.ARM64), info)
    }

    @Test
    fun `readHeaderBytes returns exactly what the stream had`() {
        val short = NativeLibArchDetector.readHeaderBytes(ByteArray(10).inputStream())
        assertEquals(10, short.size)
        val long = NativeLibArchDetector.readHeaderBytes(ByteArray(NativeLibArchDetector.HEADER_BYTES * 2).inputStream())
        assertEquals(NativeLibArchDetector.HEADER_BYTES, long.size)
        assertTrue(NativeLibArchDetector.readHeaderBytes(ByteArray(0).inputStream()).isEmpty())
    }
}
