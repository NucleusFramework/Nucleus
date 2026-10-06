package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.internal.files.contentHash
import dev.nucleusframework.desktop.application.internal.files.mangledName
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import kotlin.random.Random

class ContentHashTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `file hash is the MD5 of its content`() {
        val file = tmp.newFile("lib.jar").apply { writeText("hello") }

        assertEquals("5d41402abc4b2a76b9719d911017c592", file.contentHash())
        assertEquals("lib-5d41402abc4b2a76b9719d911017c592.jar", file.mangledName())
    }

    @Test
    fun `content spanning several read buffers hashes like a single digest`() {
        val bytes = Random(seed = 42).nextBytes(3 * DEFAULT_BUFFER_SIZE + 17)
        val file = tmp.newFile("big.jar").apply { writeBytes(bytes) }

        assertEquals(md5Hex(bytes), file.contentHash())
    }

    @Test
    fun `directory hash digests its files in relative path order`() {
        val dir = tmp.newFolder("app")
        dir.resolve("b.txt").writeText("second")
        dir.resolve("a").mkdirs()
        dir.resolve("a/c.txt").writeText("first")

        assertEquals(md5Hex("firstsecond".toByteArray()), dir.contentHash())
    }

    // Same unpadded hex as contentHash, which existing mangled file names depend on.
    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { Integer.toHexString(0xFF and it.toInt()) }
}
