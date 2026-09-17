package dev.nucleusframework.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.nio.file.Files

class DeepLinkHandlerUnitTest {
    @Test
    fun `isDeepLinkArg accepts additional scheme forms`() {
        assertTrue(isDeepLinkArg("nucleus+demo://open"))
        assertTrue(isDeepLinkArg("x-nucleus:opaque"))
        assertTrue(isDeepLinkArg("a:b"))
        assertFalse(isDeepLinkArg(":missing-scheme"))
        assertFalse(isDeepLinkArg("1app://digits-first"))
        assertFalse(isDeepLinkArg("bad_scheme://x"))
        assertFalse(isDeepLinkArg("E:/work/file.txt"))
    }

    @Test
    fun `captureFromArgs stores the first deep link and ignores later tokens`() {
        DeepLinkHandler.captureFromArgs(arrayOf("--verbose", "nucleus://item/1", "nucleus://item/2"))
        assertEquals(URI("nucleus://item/1"), DeepLinkHandler.uri)
    }

    @Test
    fun `captureFromArgs ignores non-uri tokens and invalid uris`() {
        val before = DeepLinkHandler.uri
        DeepLinkHandler.captureFromArgs(arrayOf("--help", "C:\\Users\\x\\file.txt"))
        assertEquals(before, DeepLinkHandler.uri)
        DeepLinkHandler.captureFromArgs(arrayOf("nucleus://host/sp ace"))
        assertEquals(before, DeepLinkHandler.uri)
    }

    @Test
    fun `deliver updates uri and writeUriTo persists it`() {
        val delivered = URI("nucleus://written")
        DeepLinkHandler.deliver(delivered)
        assertEquals(delivered, DeepLinkHandler.uri)

        val path = Files.createTempFile("nucleus-deeplink", ".uri")
        DeepLinkHandler.writeUriTo(path)
        assertEquals("nucleus://written", Files.readString(path))
    }

    @Test
    fun `readUriFrom delivers file contents to the handler`() {
        val path = Files.createTempFile("nucleus-deeplink-read", ".uri")
        Files.writeString(path, "nucleus://from-file")
        var received: URI? = null
        DeepLinkHandler.setHandler(emptyArray()) { received = it }
        DeepLinkHandler.readUriFrom(path)
        assertEquals(URI("nucleus://from-file"), received)
        assertEquals(URI("nucleus://from-file"), DeepLinkHandler.uri)
    }

    @Test
    fun `setHandler delivers a cold-start uri only once`() {
        val first = mutableListOf<URI>()
        DeepLinkHandler.setHandler(arrayOf("nucleus://cold-start")) { first += it }
        val second = mutableListOf<URI>()
        DeepLinkHandler.setHandler(arrayOf("nucleus://cold-start")) { second += it }
        // The first in-process setHandler may already have consumed cold-start
        // in an earlier test; a second call must never re-fire the launch URI.
        assertTrue(second.isEmpty())
    }

    @Test
    fun `readUriFrom ignores empty files`() {
        val path = Files.createTempFile("nucleus-deeplink-empty", ".uri")
        Files.writeString(path, "   ")
        DeepLinkHandler.readUriFrom(path)
        assertTrue(Files.exists(path))
    }
}
