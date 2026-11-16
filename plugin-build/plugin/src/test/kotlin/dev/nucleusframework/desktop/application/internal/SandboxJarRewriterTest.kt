/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class SandboxJarRewriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixtureClassBytes(): ByteArray {
        val res = SandboxLoadFixture::class.java.getResourceAsStream("SandboxLoadFixture.class")
            ?: error("SandboxLoadFixture.class not found")
        return res.use { it.readBytes() }
    }

    /** Builds a synthetic input JAR with a fake native lib, the fixture class, and a plain resource. */
    private fun buildInputJar(file: File): File {
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            // Fake native lib (not a real Mach-O — content does not matter for the rewriter).
            zos.putNextEntry(ZipEntry("jni/aarch64/libfoo.dylib"))
            zos.write(ByteArray(64) { it.toByte() })
            zos.closeEntry()
            // A second native lib in another arch dir to verify per-entry markers.
            zos.putNextEntry(ZipEntry("jni/amd64/libfoo.so"))
            zos.write(ByteArray(32) { (-it).toByte() })
            zos.closeEntry()
            // A class that calls System.load.
            zos.putNextEntry(ZipEntry("com/example/SandboxLoadFixture.class"))
            zos.write(fixtureClassBytes())
            zos.closeEntry()
            // A plain resource that must be preserved verbatim.
            zos.putNextEntry(ZipEntry("META-INF/plain.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }
        return file
    }

    private fun entryText(zip: File, name: String): ByteArray =
        ZipFile(zip).use { zf ->
            zf.getInputStream(zf.getEntry(name)).use { it.readBytes() }
        }

    @Test
    fun `native libs become markers and manifest maps sha to bundled filename`() {
        val input = buildInputJar(tmp.newFile("input.jar"))
        val output = tmp.newFile("output.jar")
        val mangled = "input-abcdef.jar"

        val result = SandboxJarRewriter.rewriteJar(input, output, mangled, keepReal = false)

        assertEquals(2, result.markedLibs)
        assertEquals(1, result.rewrittenClasses)
        // A marker and an original-lib entry per native lib.
        assertEquals(4, result.manifest.size)

        // Marker at the same path with the exact marker bytes.
        val markerBytes = entryText(output, "jni/aarch64/libfoo.dylib")
        assertArrayEquals(SandboxMarkers.markerBytes(mangled, "jni/aarch64/libfoo.dylib"), markerBytes)
        // Manifest entry for this marker -> flattened bundled name.
        val sha = SandboxMarkers.sha256Hex(markerBytes)
        assertEquals("libfoo.dylib", result.manifest[sha])

        val markerSo = entryText(output, "jni/amd64/libfoo.so")
        val shaSo = SandboxMarkers.sha256Hex(markerSo)
        assertEquals("libfoo.so", result.manifest[shaSo])

        // Plain resource preserved verbatim.
        assertArrayEquals("hello".toByteArray(), entryText(output, "META-INF/plain.txt"))
    }

    @Test
    fun `manifest also maps the original lib sha so a cached real copy is redirected`() {
        val input = buildInputJar(tmp.newFile("input.jar"))
        val output = tmp.newFile("output.jar")
        val result = SandboxJarRewriter.rewriteJar(input, output, "input-x.jar", keepReal = false)

        val dylibSha = SandboxMarkers.sha256Hex(entryText(input, "jni/aarch64/libfoo.dylib"))
        assertEquals("libfoo.dylib", result.manifest[dylibSha])
        val soSha = SandboxMarkers.sha256Hex(entryText(input, "jni/amd64/libfoo.so"))
        assertEquals("libfoo.so", result.manifest[soSha])
    }

    @Test
    fun `class entry is rewritten to call the shim`() {
        val input = buildInputJar(tmp.newFile("input.jar"))
        val output = tmp.newFile("output.jar")
        SandboxJarRewriter.rewriteJar(input, output, "input-x.jar", keepReal = false)

        val classBytes = entryText(output, "com/example/SandboxLoadFixture.class")
        // Both System.load and Runtime.load were rewritten to the shim on the first pass, so no
        // original load call site remains.
        assertFalse(SandboxBytecodeRewriter.hasLoadCallSite(classBytes))
        val rewritten = SandboxBytecodeRewriter.rewriteSystemLoadCalls(classBytes)
        // Already rewritten on the first pass -> second pass is a no-op.
        assertTrue(rewritten === classBytes)
    }

    @Test
    fun `keepReal copies the jar verbatim and produces no manifest entries`() {
        val input = buildInputJar(tmp.newFile("input.jar"))
        val output = tmp.newFile("output.jar")
        val result = SandboxJarRewriter.rewriteJar(input, output, "input-x.jar", keepReal = true)

        assertTrue(result.manifest.isEmpty())
        assertEquals(0, result.markedLibs)
        assertEquals(0, result.rewrittenClasses)
        // Real native lib preserved (not a marker).
        val lib = entryText(output, "jni/aarch64/libfoo.dylib")
        assertArrayEquals(ByteArray(64) { it.toByte() }, lib)
    }

    @Test
    fun `hasNativeLibs and countNativeLibs reflect the jar contents`() {
        val input = buildInputJar(tmp.newFile("input.jar"))
        assertTrue(SandboxJarRewriter.hasNativeLibs(input))
        assertEquals(2, SandboxJarRewriter.countNativeLibs(input))

        val plain = tmp.newFile("plain.jar")
        ZipOutputStream(plain.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write("Manifest-Version: 1.0\n".toByteArray())
            zos.closeEntry()
        }
        assertFalse(SandboxJarRewriter.hasNativeLibs(plain))
    }

    @Test
    fun `injectShimJar writes a valid shim jar containing the loader class`() {
        val outDir = tmp.newFolder("out")
        val shimJar = SandboxJarRewriter.injectShimJar(outDir)
        assertEquals(SandboxMarkers.SHIM_JAR_NAME, shimJar.name)
        assertTrue(shimJar.isFile)
        ZipFile(shimJar).use { zf ->
            assertNotNull(zf.getEntry("dev/nucleusframework/sandbox/NucleusSandboxLoader.class"))
        }
    }
}