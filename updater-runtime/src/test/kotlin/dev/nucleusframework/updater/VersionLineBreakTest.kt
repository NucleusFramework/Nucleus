package dev.nucleusframework.updater

import dev.nucleusframework.updater.exception.ParseException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A version is one line wherever the updater stores it (the update marker, the failed-install
 * record), so a version with a line break is rejected where it enters: the app's own version at
 * construction, an update's version before it is downloaded.
 */
class VersionLineBreakTest {
    @Test
    fun `a current version with a line break is rejected at construction`() {
        for (version in listOf("2.0.0\n-beta", "2.0.0\r-beta", "2.0.0\r\n-beta")) {
            val failure =
                assertThrows("'$version' must be rejected", IllegalArgumentException::class.java) {
                    NucleusUpdater {
                        currentVersion = version
                        provider = FakeUpdateProvider()
                    }
                }
            assertTrue(
                "the error must point at currentVersion, got: ${failure.message}",
                failure.message.orEmpty().contains("currentVersion"),
            )
        }
    }

    @Test
    fun `surrounding whitespace is not a line break`() {
        val updater =
            NucleusUpdater {
                currentVersion = "2.0.0\n"
                provider = FakeUpdateProvider()
            }
        assertEquals("2.0.0\n", updater.currentVersion)
    }

    @Test
    fun `an update version with a line break is rejected before downloading`() {
        val updater =
            NucleusUpdater {
                currentVersion = "1.0.0"
                provider = FakeUpdateProvider()
            }
        val file = UpdateFile(url = "http://127.0.0.1:9/App.zip", sha512 = "hash", size = 10, fileName = "App.zip")
        val info = UpdateInfo(version = "2.0.0\nexitCode=0", releaseDate = "", files = listOf(file), currentFile = file)

        assertThrows(ParseException::class.java) {
            runBlocking { updater.downloadUpdate(info).toList() }
        }
    }
}
