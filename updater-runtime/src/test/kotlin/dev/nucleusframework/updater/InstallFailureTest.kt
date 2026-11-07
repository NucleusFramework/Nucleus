package dev.nucleusframework.updater

import dev.nucleusframework.updater.internal.InstallFailureRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InstallFailureTest {
    private lateinit var updater: NucleusUpdater

    @Before
    fun setup() {
        updater =
            NucleusUpdater {
                currentVersion = "2.0.0"
                provider = FakeUpdateProvider()
            }
        cleanup()
    }

    @After
    fun cleanup() {
        InstallFailureRecord.delete()
        InstallFailureRecord.logFile().delete()
    }

    private fun record(text: String) {
        InstallFailureRecord.recordFile().apply {
            parentFile.mkdirs()
            writeText(text)
        }
    }

    @Test
    fun `consumeInstallFailure returns null when no install failed`() {
        assertNull(updater.consumeInstallFailure())
    }

    @Test
    fun `consumeInstallFailure reports the version, exit code and installer output`() {
        record("version=2.1.0\nrunningVersion=2.0.0\nexitCode=2\n")
        InstallFailureRecord.logFile().writeText("dpkg: error: dpkg frontend lock was locked by another process\n")

        assertEquals(
            InstallFailure("2.1.0", 2, "dpkg: error: dpkg frontend lock was locked by another process"),
            updater.consumeInstallFailure(),
        )
    }

    @Test
    fun `consumeInstallFailure clears the record but keeps the log`() {
        record("version=2.1.0\nrunningVersion=2.0.0\nexitCode=126\n")
        InstallFailureRecord.logFile().writeText("Error executing command as another user\n")
        updater.consumeInstallFailure()

        assertNull(updater.consumeInstallFailure())
        assertFalse(InstallFailureRecord.recordFile().exists())
        assertEquals("Error executing command as another user\n", InstallFailureRecord.logFile().readText())
    }

    @Test
    fun `consumeInstallFailure reports an unknown version as null`() {
        record("version=\nrunningVersion=2.0.0\nexitCode=1\n")

        assertEquals(InstallFailure(null, 1, ""), updater.consumeInstallFailure())
    }

    @Test
    fun `consumeInstallFailure keeps only the end of a long log`() {
        record("version=2.1.0\nrunningVersion=2.0.0\nexitCode=1\n")
        InstallFailureRecord.logFile().writeText("x".repeat(100_000) + "\nthe error")

        val output = updater.consumeInstallFailure()!!.output
        assertEquals(InstallFailureRecord.MAX_OUTPUT_BYTES, output.length)
        assertEquals("the error", output.substringAfterLast('\n'))
    }

    @Test
    fun `consumeInstallFailure ignores a failure once the target version is running`() {
        // The install of 2.0.0 failed on 1.9.0, then 2.0.0 was installed some other way.
        record("version=2.0.0\nrunningVersion=1.9.0\nexitCode=2\n")

        assertNull(updater.consumeInstallFailure())
        assertFalse(InstallFailureRecord.recordFile().exists())
    }

    @Test
    fun `consumeInstallFailure ignores a failure once a later version is running`() {
        // The install of 1.9.5 failed on 1.9.0, then 2.0.0 was installed by hand.
        record("version=1.9.5\nrunningVersion=1.9.0\nexitCode=2\n")

        assertNull(updater.consumeInstallFailure())
        assertFalse(InstallFailureRecord.recordFile().exists())
    }

    @Test
    fun `consumeInstallFailure ignores a record without the running version`() {
        record("version=2.1.0\nexitCode=2\n")

        assertNull(updater.consumeInstallFailure())
    }

    @Test
    fun `consumeInstallFailure does not start the output inside a character`() {
        record("version=2.1.0\nrunningVersion=2.0.0\nexitCode=1\n")
        // 50,001 bytes: characters start at offsets 1 or 3 mod 5 (é is 2 bytes, € is 3), and the
        // 16 KiB tail starts at 33,617, which is neither, so the cut lands inside a character.
        InstallFailureRecord.logFile().writeText("x" + "é€".repeat(10_000))

        val output = updater.consumeInstallFailure()!!.output
        assertFalse("the output must not start with a replacement character", output.startsWith("�"))
        assertTrue(output.endsWith("é€"))
    }

    @Test
    fun `consumeInstallFailure ignores a malformed record`() {
        record("exitCode=oops\n")

        assertNull(updater.consumeInstallFailure())
    }
}
