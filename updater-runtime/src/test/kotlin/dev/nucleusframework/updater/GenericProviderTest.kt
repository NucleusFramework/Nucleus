package dev.nucleusframework.updater

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.provider.GenericProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericProviderTest {
    @Test
    fun `rejects a plain http base url on a public host`() {
        assertThrows(IllegalArgumentException::class.java) {
            GenericProvider("http://updates.example.com")
        }
    }

    @Test
    fun `rejects a non http scheme`() {
        assertThrows(IllegalArgumentException::class.java) {
            GenericProvider("ftp://updates.example.com")
        }
    }

    @Test
    fun `allows plain http only for loopback hosts`() {
        // Local integration tests (e.g. the delta RangeHttpServer) serve fixtures over loopback http.
        assertEquals("http://127.0.0.1:8080", GenericProvider("http://127.0.0.1:8080").baseUrl)
        assertEquals("http://localhost:8080", GenericProvider("http://localhost:8080").baseUrl)
    }

    @Test
    fun `trims a trailing slash from the base url`() {
        val provider = GenericProvider("https://updates.example.com/channel/")
        assertEquals("https://updates.example.com/channel/", provider.baseUrl)
        assertEquals(
            "https://updates.example.com/channel/latest-linux.yml",
            provider.getUpdateMetadataUrl("latest", Platform.Linux),
        )
        assertEquals(
            "https://updates.example.com/channel/App-1.2.3.deb",
            provider.getDownloadUrl("App-1.2.3.deb", "1.2.3"),
        )
    }

    @Test
    fun `platform suffix matches electron-builder yml names`() {
        val provider = GenericProvider("https://cdn.example.com")
        assertEquals("https://cdn.example.com/latest.yml", provider.getUpdateMetadataUrl("latest", Platform.Windows))
        assertEquals("https://cdn.example.com/beta-mac.yml", provider.getUpdateMetadataUrl("beta", Platform.MacOS))
        assertEquals("https://cdn.example.com/alpha-linux.yml", provider.getUpdateMetadataUrl("alpha", Platform.Linux))
        assertEquals("https://cdn.example.com/latest.yml", provider.getUpdateMetadataUrl("latest", Platform.Unknown))
    }

    @Test
    fun `default auth headers are empty`() {
        assertTrue(GenericProvider("https://cdn.example.com").authHeaders().isEmpty())
    }

    @Test
    fun `default block map url appends the extension`() {
        val provider = GenericProvider("https://cdn.example.com")
        assertEquals(
            "https://cdn.example.com/App.zip.blockmap",
            provider.getBlockMapUrl("https://cdn.example.com/App.zip"),
        )
    }
}
