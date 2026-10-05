package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.SigningAlgorithm
import org.gradle.api.GradleException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class WindowsAppImageSignerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `an empty security directory reads as unsigned`() {
        assertTrue(PeSignature.isUnsignedPe(pe(plus = true, securitySize = 0)))
        assertTrue(PeSignature.isUnsignedPe(pe(plus = false, securitySize = 0)))
    }

    @Test
    fun `a populated security directory reads as signed`() {
        assertFalse(PeSignature.isUnsignedPe(pe(plus = true, securitySize = 0x2A10)))
        assertFalse(PeSignature.isUnsignedPe(pe(plus = false, securitySize = 0x2A10)))
    }

    @Test
    fun `files are read the same way as bytes`() {
        val unsigned = tmp.newFile("a.dll").apply { writeBytes(pe(plus = true, securitySize = 0)) }
        val signed = tmp.newFile("b.dll").apply { writeBytes(pe(plus = true, securitySize = 64)) }
        assertTrue(PeSignature.isUnsignedPe(unsigned))
        assertFalse(PeSignature.isUnsignedPe(signed))
    }

    @Test
    fun `non-PE data is never a signing candidate`() {
        assertFalse(PeSignature.isUnsignedPe(ByteArray(0)))
        assertFalse(PeSignature.isUnsignedPe("MZ but nothing else".toByteArray()))
        assertFalse(PeSignature.isUnsignedPe(ByteArray(512)))
        assertFalse(PeSignature.isUnsignedPe(pe(plus = true, securitySize = 0).also { it[0x3C] = 0x7F }))
    }

    @Test
    fun `sha256 timestamps over RFC 3161 with the configured server`() {
        val args =
            WindowsAppImageSigner.signToolArgs(
                algorithm = SigningAlgorithm.Sha256,
                timestampServer = "http://ts.example",
                certificateArgs = listOf("/f", "cert.pfx"),
                description = "My App",
                password = "secret",
                offline = false,
            )
        assertEquals(
            listOf(
                "sign", "/tr", "http://ts.example", "/f", "cert.pfx", "/fd", "sha256",
                "/td", "sha256", "/d", "My App", "/p", "secret",
            ),
            args,
        )
    }

    @Test
    fun `sha1 uses the legacy timestamp protocol and electron-builder's default server`() {
        val args =
            WindowsAppImageSigner.signToolArgs(
                algorithm = SigningAlgorithm.Sha1,
                timestampServer = "http://ts.example",
                certificateArgs = listOf("/sha1", "ABC", "/s", "My"),
                description = "My App",
                password = null,
                offline = false,
            )
        assertEquals(
            listOf(
                "sign", "/t", "http://timestamp.digicert.com", "/sha1", "ABC", "/s", "My",
                "/fd", "sha1", "/d", "My App",
            ),
            args,
        )
    }

    @Test
    fun `offline signing skips the timestamp`() {
        val args =
            WindowsAppImageSigner.signToolArgs(
                algorithm = SigningAlgorithm.Sha256,
                timestampServer = null,
                certificateArgs = listOf("/f", "cert.pfx"),
                description = "My App",
                password = null,
                offline = true,
            )
        assertEquals(listOf("sign", "/f", "cert.pfx", "/fd", "sha256", "/d", "My App"), args)
    }

    @Test
    fun `files are split so that no command line overflows`() {
        val files = (0 until 2_000).map { File("C:\\app\\runtime\\bin\\library-number-$it.dll") }
        val chunks = WindowsAppImageSigner.chunked(files, listOf("sign", "/fd", "sha256"))
        assertTrue(chunks.size > 1)
        assertEquals(files, chunks.flatten())
        chunks.forEach { chunk -> assertTrue(chunk.sumOf { it.absolutePath.length + 3 } < 30_000) }
        assertEquals(emptyList<List<File>>(), WindowsAppImageSigner.chunked(emptyList(), emptyList()))
    }

    @Test
    fun `a base64 certificate is decoded, line breaks included`() {
        val bytes = byteArrayOf(0x30, 0x82.toByte(), 0x0A, 0x1F, 0x02, 0x01, 0x03)
        val encoded = Base64.getMimeEncoder(4, "\r\n".toByteArray()).encodeToString(bytes)
        assertArrayEquals(bytes, WindowsAppImageSigner.decodeBase64Certificate(encoded))
    }

    @Test
    fun `a data URL certificate is decoded`() {
        val bytes = byteArrayOf(0x30, 0x82.toByte(), 0x01)
        val link = "data:application/x-pkcs12;base64," + Base64.getEncoder().encodeToString(bytes)
        assertArrayEquals(bytes, WindowsAppImageSigner.decodeBase64Certificate(link))
    }

    @Test
    fun `a link that is not base64 is rejected instead of decoded into garbage`() {
        listOf("https://example.com/cert.p12", "C:\\certs\\missing.pfx", "", "   ").forEach { link ->
            assertThrows(GradleException::class.java) { WindowsAppImageSigner.decodeBase64Certificate(link) }
        }
    }

    /** A minimal PE header: DOS stub pointer, PE signature, COFF header and optional header. */
    private fun pe(
        plus: Boolean,
        securitySize: Int,
    ): ByteArray {
        val peOffset = 0x80
        val optionalHeader = peOffset + 4 + 20
        val directories = optionalHeader + if (plus) 112 else 96
        val buffer = ByteBuffer.allocate(directories + 16 * 8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0, 0x5A4D)
        buffer.putInt(0x3C, peOffset)
        buffer.putInt(peOffset, 0x00004550)
        buffer.putShort(optionalHeader, if (plus) 0x20B.toShort() else 0x10B.toShort())
        buffer.putInt(directories - 4, 16)
        buffer.putInt(directories + 4 * 8, if (securitySize == 0) 0 else 0x1000)
        buffer.putInt(directories + 4 * 8 + 4, securitySize)
        return buffer.array()
    }
}
