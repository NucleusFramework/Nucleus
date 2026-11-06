/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeLibArchDetector {
    enum class NativeArch { X86, X64, ARM64, UNIVERSAL, OTHER, UNKNOWN }

    enum class NativeOs { WINDOWS, LINUX, MACOS, OTHER, UNKNOWN }

    data class NativeInfo(
        val os: NativeOs,
        val arch: NativeArch,
    )

    /**
     * Bytes to read from a native lib for [detectFromHeader] to reach the machine field.
     * The PE one is the furthest away: it sits at `e_lfanew + 4`, and `e_lfanew` is routinely
     * past the first 64 bytes (0x78 in the zstd-kmp dlls, for instance).
     */
    const val HEADER_BYTES = 4096

    private val NATIVE_EXTENSIONS = setOf(".dll", ".so", ".dylib", ".jnilib")

    fun isNativeLib(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return NATIVE_EXTENSIONS.any { lower.endsWith(it) }
    }

    // --- Entry resolution (path + header) ---

    /**
     * Resolve a native lib entry, preferring path tokens and completing whatever they leave
     * unresolved with the binary header.
     *
     * Neither source is sufficient on its own: `jni/aarch64/zstd-kmp.dll` names an arch but no
     * OS, while a flat `libfoo.dylib` names neither. Replacing one with the other (rather than
     * merging) let a wrong-arch lib pass the packaging filters, see issue #399.
     *
     * [readHeader] is invoked at most once, only when the path leaves a field unresolved, and
     * must return up to [HEADER_BYTES] bytes from the start of the entry (empty if unreadable).
     */
    fun detectEntry(
        entryPath: String,
        readHeader: () -> ByteArray,
    ): NativeInfo {
        val pathInfo = detectFromPath(entryPath)
        if (pathInfo.os != NativeOs.UNKNOWN && pathInfo.arch != NativeArch.UNKNOWN) {
            return pathInfo
        }
        val header = readHeader()
        if (header.isEmpty()) return pathInfo
        val headerInfo = detectFromHeader(header)
        return NativeInfo(
            os = if (pathInfo.os != NativeOs.UNKNOWN) pathInfo.os else headerInfo.os,
            arch = if (pathInfo.arch != NativeArch.UNKNOWN) pathInfo.arch else headerInfo.arch,
        )
    }

    /** Reads up to [HEADER_BYTES] bytes from [input], returning exactly what was available. */
    fun readHeaderBytes(input: java.io.InputStream): ByteArray {
        val buffer = ByteArray(HEADER_BYTES)
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return if (totalRead == buffer.size) buffer else buffer.copyOf(totalRead)
    }

    // --- Path-based detection ---

    private data class OsToken(
        val token: String,
        val os: NativeOs,
    )

    private data class ArchToken(
        val token: String,
        val arch: NativeArch,
    )

    private val OS_TOKENS =
        listOf(
            // Specific compound tokens must come before their prefixes
            OsToken("linux-android", NativeOs.OTHER),
            OsToken("linux-musl", NativeOs.LINUX),
            OsToken("win32", NativeOs.WINDOWS),
            OsToken("windows", NativeOs.WINDOWS),
            OsToken("win", NativeOs.WINDOWS),
            OsToken("darwin", NativeOs.MACOS),
            OsToken("macos", NativeOs.MACOS),
            OsToken("mac", NativeOs.MACOS),
            OsToken("osx", NativeOs.MACOS),
            OsToken("linux", NativeOs.LINUX),
            OsToken("freebsd", NativeOs.OTHER),
            OsToken("openbsd", NativeOs.OTHER),
            OsToken("dragonflybsd", NativeOs.OTHER),
            OsToken("aix", NativeOs.OTHER),
            OsToken("sunos", NativeOs.OTHER),
        )

    private val ARCH_TOKENS =
        listOf(
            ArchToken("x86-64", NativeArch.X64),
            ArchToken("x86_64", NativeArch.X64),
            ArchToken("amd64", NativeArch.X64),
            ArchToken("x64", NativeArch.X64),
            ArchToken("aarch64", NativeArch.ARM64),
            ArchToken("arm64", NativeArch.ARM64),
            ArchToken("x86", NativeArch.X86),
            ArchToken("i386", NativeArch.X86),
            ArchToken("arm", NativeArch.OTHER),
            ArchToken("armel", NativeArch.OTHER),
            ArchToken("armv6", NativeArch.OTHER),
            ArchToken("armv7", NativeArch.OTHER),
            ArchToken("ppc", NativeArch.OTHER),
            ArchToken("ppc64", NativeArch.OTHER),
            ArchToken("ppc64le", NativeArch.OTHER),
            ArchToken("s390x", NativeArch.OTHER),
            ArchToken("riscv64", NativeArch.OTHER),
            ArchToken("mips64", NativeArch.OTHER),
            ArchToken("mips64el", NativeArch.OTHER),
            ArchToken("loongarch64", NativeArch.OTHER),
            ArchToken("sparc", NativeArch.OTHER),
            ArchToken("sparcv9", NativeArch.OTHER),
        )

    /**
     * Detect platform from a JAR entry path by analyzing path segments.
     * Returns non-UNKNOWN info only when the path clearly indicates a platform-specific directory.
     */
    fun detectFromPath(entryPath: String): NativeInfo {
        // Split into path segments and the compound tokens within segments
        // e.g. "com/sun/jna/linux-x86-64/libjnidispatch.so"
        //   → segments: [com, sun, jna, linux-x86-64, libjnidispatch.so]
        val segments = entryPath.split('/')

        var detectedOs = NativeOs.UNKNOWN
        var detectedArch = NativeArch.UNKNOWN

        for (segment in segments) {
            val lower = segment.lowercase()

            // Try longer/more specific tokens first — sorted by length desc
            if (detectedOs == NativeOs.UNKNOWN) {
                for (osToken in OS_TOKENS) {
                    if (matchesToken(lower, osToken.token)) {
                        detectedOs = osToken.os
                        break
                    }
                }
            }

            if (detectedArch == NativeArch.UNKNOWN) {
                for (archToken in ARCH_TOKENS) {
                    if (matchesToken(lower, archToken.token)) {
                        detectedArch = archToken.arch
                        break
                    }
                }
            }

            // Some segments encode both OS and arch, e.g. "linux-x86-64" or "win32-x86-64"
            // Re-check same segment for arch using delimiter-aware matching
            if (detectedOs != NativeOs.UNKNOWN && detectedArch == NativeArch.UNKNOWN) {
                for (archToken in ARCH_TOKENS) {
                    if (matchesToken(lower, archToken.token)) {
                        detectedArch = archToken.arch
                        break
                    }
                }
            }
        }

        return NativeInfo(detectedOs, detectedArch)
    }

    /** Check if a path segment matches a token as a whole or as a delimited part */
    private fun matchesToken(
        segment: String,
        token: String,
    ): Boolean {
        if (segment == token) return true
        // Match as a delimited part: "linux-x86-64" should match "linux" and "x86-64"
        // Delimiters: -, _, .
        val idx = segment.indexOf(token)
        if (idx < 0) return false
        val before = if (idx > 0) segment[idx - 1] else '-'
        val after = if (idx + token.length < segment.length) segment[idx + token.length] else '-'
        return (before == '-' || before == '_' || before == '.') &&
            (after == '-' || after == '_' || after == '.')
    }

    // --- Binary header detection ---

    fun detectFromHeader(bytes: ByteArray): NativeInfo {
        if (bytes.size < MAGIC_SIZE) return NativeInfo(NativeOs.UNKNOWN, NativeArch.UNKNOWN)

        // PE (.dll) — starts with MZ
        if (bytes[0] == PE_MAGIC_M && bytes[1] == PE_MAGIC_Z) {
            return detectPE(bytes)
        }

        // ELF (.so) — starts with 0x7F ELF
        if (bytes[0] == ELF_MAGIC_0 &&
            bytes[1] == ELF_MAGIC_E &&
            bytes[2] == ELF_MAGIC_L &&
            bytes[ELF_MAGIC_F_INDEX] == ELF_MAGIC_F
        ) {
            return detectELF(bytes)
        }

        // Mach-O / fat binary. `magic` is the first 4 bytes read big-endian, so it tells us how
        // the file itself is laid out: reading back MH_MAGIC_64 (0xFEEDFACF) means the file stores
        // it big-endian, while the byte-swapped 0xCFFAEDFE means little-endian — which is what
        // every macOS x86_64/arm64 dylib on disk actually starts with ("cf fa ed fe"). The rest of
        // the header, cpu_type included, must be read in that same order.
        val magic = ByteBuffer.wrap(bytes, 0, MAGIC_SIZE).int
        return when (magic) {
            MH_MAGIC_64 -> detectMachO(bytes, ByteOrder.BIG_ENDIAN)
            MH_CIGAM_64 -> detectMachO(bytes, ByteOrder.LITTLE_ENDIAN)
            MH_MAGIC -> detectMachO(bytes, ByteOrder.BIG_ENDIAN)
            MH_CIGAM -> detectMachO(bytes, ByteOrder.LITTLE_ENDIAN)
            // Fat headers are always big-endian; 0xCAFEBABF is the 64-bit variant.
            FAT_MAGIC, FAT_MAGIC_64 -> NativeInfo(NativeOs.MACOS, NativeArch.UNIVERSAL)
            else -> NativeInfo(NativeOs.UNKNOWN, NativeArch.UNKNOWN)
        }
    }

    private fun detectPE(bytes: ByteArray): NativeInfo {
        if (bytes.size < DOS_HEADER_SIZE) return NativeInfo(NativeOs.WINDOWS, NativeArch.UNKNOWN)
        val peOffset = ByteBuffer.wrap(bytes, DOS_E_LFANEW_OFFSET, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int
        val machineOffset = peOffset + PE_SIGNATURE_SIZE
        if (bytes.size < machineOffset + Short.SIZE_BYTES) return NativeInfo(NativeOs.WINDOWS, NativeArch.UNKNOWN)
        val machine =
            ByteBuffer
                .wrap(bytes, machineOffset, Short.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short
                .toInt() and UNSIGNED_SHORT_MASK
        val arch =
            when (machine) {
                IMAGE_FILE_MACHINE_AMD64 -> NativeArch.X64
                IMAGE_FILE_MACHINE_I386 -> NativeArch.X86
                IMAGE_FILE_MACHINE_ARM64 -> NativeArch.ARM64
                else -> NativeArch.UNKNOWN
            }
        return NativeInfo(NativeOs.WINDOWS, arch)
    }

    private fun detectELF(bytes: ByteArray): NativeInfo {
        if (bytes.size < ELF_E_MACHINE_OFFSET + Short.SIZE_BYTES) return NativeInfo(NativeOs.LINUX, NativeArch.UNKNOWN)
        val order = if (bytes[ELF_EI_DATA_INDEX] == ELF_DATA_2MSB) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val eMachine =
            ByteBuffer
                .wrap(bytes, ELF_E_MACHINE_OFFSET, Short.SIZE_BYTES)
                .order(order)
                .short
                .toInt() and UNSIGNED_SHORT_MASK
        val arch =
            when (eMachine) {
                EM_X86_64 -> NativeArch.X64
                EM_AARCH64 -> NativeArch.ARM64
                EM_386 -> NativeArch.X86
                else -> NativeArch.UNKNOWN
            }
        return NativeInfo(NativeOs.LINUX, arch)
    }

    private fun detectMachO(
        bytes: ByteArray,
        order: ByteOrder,
    ): NativeInfo {
        if (bytes.size < MACHO_CPU_TYPE_OFFSET + Int.SIZE_BYTES) return NativeInfo(NativeOs.MACOS, NativeArch.UNKNOWN)
        val cpuType = ByteBuffer.wrap(bytes, MACHO_CPU_TYPE_OFFSET, Int.SIZE_BYTES).order(order).int
        val arch =
            when (cpuType) {
                CPU_TYPE_X86_64 -> NativeArch.X64
                CPU_TYPE_ARM64 -> NativeArch.ARM64
                else -> NativeArch.UNKNOWN
            }
        return NativeInfo(NativeOs.MACOS, arch)
    }

    // Every format is identified by its first 4 bytes.
    private const val MAGIC_SIZE = 4
    private const val UNSIGNED_SHORT_MASK = 0xFFFF

    // PE: "MZ" DOS header, e_lfanew (offset of the "PE\0\0" signature) at 0x3C, COFF Machine right after it.
    private const val PE_MAGIC_M = 0x4D.toByte()
    private const val PE_MAGIC_Z = 0x5A.toByte()
    private const val DOS_HEADER_SIZE = 0x40
    private const val DOS_E_LFANEW_OFFSET = 0x3C
    private const val PE_SIGNATURE_SIZE = 4
    private const val IMAGE_FILE_MACHINE_AMD64 = 0x8664
    private const val IMAGE_FILE_MACHINE_I386 = 0x014C
    private const val IMAGE_FILE_MACHINE_ARM64 = 0xAA64

    // ELF: "\x7FELF", EI_DATA (byte order) at index 5, e_machine at offset 18.
    private const val ELF_MAGIC_0 = 0x7F.toByte()
    private const val ELF_MAGIC_E = 0x45.toByte()
    private const val ELF_MAGIC_L = 0x4C.toByte()
    private const val ELF_MAGIC_F = 0x46.toByte()
    private const val ELF_MAGIC_F_INDEX = 3
    private const val ELF_EI_DATA_INDEX = 5
    private const val ELF_DATA_2MSB = 2.toByte()
    private const val ELF_E_MACHINE_OFFSET = 18
    private const val EM_386 = 0x03
    private const val EM_X86_64 = 0x3E
    private const val EM_AARCH64 = 0xB7

    // Mach-O: magic read big-endian (CIGAM = byte-swapped), cpu_type right after it.
    private const val MH_MAGIC = 0xFEEDFACE.toInt()
    private const val MH_CIGAM = 0xCEFAEDFE.toInt()
    private const val MH_MAGIC_64 = 0xFEEDFACF.toInt()
    private const val MH_CIGAM_64 = 0xCFFAEDFE.toInt()
    private const val FAT_MAGIC = 0xCAFEBABE.toInt()
    private const val FAT_MAGIC_64 = 0xCAFEBABF.toInt()
    private const val MACHO_CPU_TYPE_OFFSET = 4
    private const val CPU_TYPE_X86_64 = 0x01000007
    private const val CPU_TYPE_ARM64 = 0x0100000C
}
