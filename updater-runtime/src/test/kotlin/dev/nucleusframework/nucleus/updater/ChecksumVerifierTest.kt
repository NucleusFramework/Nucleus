package dev.nucleusframework.nucleus.updater

import dev.nucleusframework.nucleus.updater.internal.ChecksumVerifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.Base64

class ChecksumVerifierTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `verify correct checksum`() {
        val file = tempFolder.newFile("test.bin")
        val content = "Hello, World!".toByteArray()
        file.writeBytes(content)

        val expectedHash =
            Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-512").digest(content),
            )

        assertTrue(ChecksumVerifier.verify(file, expectedHash))
    }

    @Test
    fun `verify incorrect checksum`() {
        val file = tempFolder.newFile("test.bin")
        file.writeBytes("Hello, World!".toByteArray())

        assertFalse(ChecksumVerifier.verify(file, "wronghash"))
    }

    @Test
    fun `compute sha512 returns base64 string`() {
        val file = tempFolder.newFile("test.bin")
        file.writeBytes("test content".toByteArray())

        val result = ChecksumVerifier.computeSha512Base64(file)
        assertNotNull(result)
        assertTrue(result.isNotEmpty())

        // Verify it's valid base64
        val decoded = Base64.getDecoder().decode(result)
        assertTrue(decoded.size == 64) // SHA-512 produces 64 bytes
    }

    @Test
    fun `verify empty file`() {
        val file = tempFolder.newFile("empty.bin")

        val expectedHash =
            Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-512").digest(ByteArray(0)),
            )

        assertTrue(ChecksumVerifier.verify(file, expectedHash))
    }

    @Test
    fun `verify large file`() {
        val file = tempFolder.newFile("large.bin")
        val content = ByteArray(1_000_000) { (it % 256).toByte() }
        file.writeBytes(content)

        val expectedHash =
            Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-512").digest(content),
            )

        assertTrue(ChecksumVerifier.verify(file, expectedHash))
    }
}
