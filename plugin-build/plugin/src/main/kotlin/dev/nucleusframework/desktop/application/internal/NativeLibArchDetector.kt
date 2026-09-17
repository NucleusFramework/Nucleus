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
        // e.g. "com/sun/jna/linux-x86-64/libjnidispatch.so" → segments: [com, sun, jna, linux-x86-64, libjnidispatch.so]
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
        if (bytes.size < 4) return NativeInfo(NativeOs.UNKNOWN, NativeArch.UNKNOWN)

        // PE (.dll) — starts with MZ
        if (bytes[0] == 0x4D.toByte() && bytes[1] == 0x5A.toByte()) {
            return detectPE(bytes)
        }

        // ELF (.so) — starts with 0x7F ELF
        if (bytes[0] == 0x7F.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0x4C.toByte() &&
            bytes[3] == 0x46.toByte()
        ) {
            return detectELF(bytes)
        }

        // Mach-O / fat binary. `magic` is the first 4 bytes read big-endian, so it tells us how
        // the file itself is laid out: reading back MH_MAGIC_64 (0xFEEDFACF) means the file stores
        // it big-endian, while the byte-swapped 0xCFFAEDFE means little-endian — which is what
        // every macOS x86_64/arm64 dylib on disk actually starts with ("cf fa ed fe"). The rest of
        // the header, cpu_type included, must be read in that same order.
        val magic = ByteBuffer.wrap(bytes, 0, 4).int
        return when (magic) {
            0xFEEDFACF.toInt() -> detectMachO(bytes, ByteOrder.BIG_ENDIAN)
            0xCFFAEDFE.toInt() -> detectMachO(bytes, ByteOrder.LITTLE_ENDIAN)
            0xFEEDFACE.toInt() -> detectMachO(bytes, ByteOrder.BIG_ENDIAN)
            0xCEFAEDFE.toInt() -> detectMachO(bytes, ByteOrder.LITTLE_ENDIAN)
            // Fat headers are always big-endian; 0xCAFEBABF is the 64-bit variant.
            0xCAFEBABE.toInt(), 0xCAFEBABF.toInt() -> NativeInfo(NativeOs.MACOS, NativeArch.UNIVERSAL)
            else -> NativeInfo(NativeOs.UNKNOWN, NativeArch.UNKNOWN)
        }
    }

    private fun detectPE(bytes: ByteArray): NativeInfo {
        if (bytes.size < 0x40) return NativeInfo(NativeOs.WINDOWS, NativeArch.UNKNOWN)
        val peOffset = ByteBuffer.wrap(bytes, 0x3C, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val machineOffset = peOffset + 4
        if (bytes.size < machineOffset + 2) return NativeInfo(NativeOs.WINDOWS, NativeArch.UNKNOWN)
        val machine =
            ByteBuffer
                .wrap(bytes, machineOffset, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short
                .toInt() and 0xFFFF
        val arch =
            when (machine) {
                0x8664 -> NativeArch.X64
                0x014C -> NativeArch.X86
                0xAA64 -> NativeArch.ARM64
                else -> NativeArch.UNKNOWN
            }
        return NativeInfo(NativeOs.WINDOWS, arch)
    }

    private fun detectELF(bytes: ByteArray): NativeInfo {
        if (bytes.size < 20) return NativeInfo(NativeOs.LINUX, NativeArch.UNKNOWN)
        val order = if (bytes[5] == 2.toByte()) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val eMachine =
            ByteBuffer
                .wrap(bytes, 18, 2)
                .order(order)
                .short
                .toInt() and 0xFFFF
        val arch =
            when (eMachine) {
                0x3E -> NativeArch.X64
                0xB7 -> NativeArch.ARM64
                0x03 -> NativeArch.X86
                else -> NativeArch.UNKNOWN
            }
        return NativeInfo(NativeOs.LINUX, arch)
    }

    private fun detectMachO(
        bytes: ByteArray,
        order: ByteOrder,
    ): NativeInfo {
        if (bytes.size < 8) return NativeInfo(NativeOs.MACOS, NativeArch.UNKNOWN)
        val cpuType = ByteBuffer.wrap(bytes, 4, 4).order(order).int
        val arch =
            when (cpuType) {
                0x01000007 -> NativeArch.X64
                0x0100000C -> NativeArch.ARM64
                else -> NativeArch.UNKNOWN
            }
        return NativeInfo(NativeOs.MACOS, arch)
    }
}
