package dev.nucleusframework.updater

import dev.nucleusframework.updater.exception.ChecksumException
import dev.nucleusframework.updater.exception.NetworkException
import dev.nucleusframework.updater.exception.NoMatchingFileException
import dev.nucleusframework.updater.exception.ParseException
import dev.nucleusframework.updater.exception.UpdateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateExceptionTest {
    @Test
    fun `UpdateException keeps message and cause`() {
        val cause = IllegalStateException("boom")
        val ex = UpdateException("failed", cause)
        assertEquals("failed", ex.message)
        assertSame(cause, ex.cause)
        assertNull(UpdateException("plain").cause)
    }

    @Test
    fun `NetworkException is an UpdateException`() {
        val cause = RuntimeException("timeout")
        val ex = NetworkException("offline", cause)
        assertEquals("offline", ex.message)
        assertSame(cause, ex.cause)
        val asUpdate: UpdateException = ex
        assertEquals("offline", asUpdate.message)
    }

    @Test
    fun `ChecksumException embeds expected and actual hashes`() {
        val ex = ChecksumException("abc", "def")
        assertEquals("SHA-512 mismatch: expected=abc, actual=def", ex.message)
        val asUpdate: UpdateException = ex
        assertTrue(asUpdate.message!!.contains("expected=abc"))
    }

    @Test
    fun `NoMatchingFileException names the requested triple`() {
        val ex = NoMatchingFileException("MacOS", "ARM64", "dmg")
        assertEquals("No matching file for MacOS/ARM64/dmg", ex.message)
    }

    @Test
    fun `ParseException is an UpdateException`() {
        val ex = ParseException("Missing 'version' field in YAML metadata")
        assertEquals("Missing 'version' field in YAML metadata", ex.message)
        val asUpdate: UpdateException = ex
        assertEquals(ex.message, asUpdate.message)
    }

    @Test
    fun `UpdateResult Error wraps the exception`() {
        val ex = NetworkException("HTTP 503")
        val result = UpdateResult.Error(ex)
        assertSame(ex, result.exception)
    }

    @Test
    fun `UpdateLevel contains the four documented values`() {
        assertEquals(
            listOf(UpdateLevel.MAJOR, UpdateLevel.MINOR, UpdateLevel.PATCH, UpdateLevel.PRE_RELEASE),
            UpdateLevel.entries,
        )
    }

    @Test
    fun `DownloadProgress defaults file to null and differential to false`() {
        val progress = DownloadProgress(bytesDownloaded = 10, totalBytes = 40, percent = 25.0)
        assertEquals(10, progress.bytesDownloaded)
        assertEquals(40, progress.totalBytes)
        assertEquals(25.0, progress.percent, 0.0)
        assertNull(progress.file)
        assertEquals(false, progress.isDifferential)
    }
}
