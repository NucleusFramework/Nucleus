package dev.nucleusframework.updater

import dev.nucleusframework.updater.internal.buildLinuxAppImageUpdateScript
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxAppImageScriptFlagsTest {
    @Test
    fun `restart and alreadyReplaced flags are interpolated as 0 or 1`() {
        val restarting =
            buildLinuxAppImageUpdateScript(
                newFile = "/tmp/new.AppImage",
                oldFile = "/tmp/old.AppImage",
                appPid = 42L,
                logFile = "/tmp/update.log",
                restart = true,
                alreadyReplaced = true,
                selfDelete = true,
            )
        assertTrue(restarting.contains("RESTART=1"))
        assertTrue(restarting.contains("ALREADY_REPLACED=1"))
        assertTrue(restarting.contains("rm -f \"\$0\""))

        val quiet =
            buildLinuxAppImageUpdateScript(
                newFile = "/tmp/new.AppImage",
                oldFile = "/tmp/old.AppImage",
                appPid = 42L,
                logFile = "/tmp/update.log",
                restart = false,
                alreadyReplaced = false,
                selfDelete = false,
            )
        assertTrue(quiet.contains("RESTART=0"))
        assertTrue(quiet.contains("ALREADY_REPLACED=0"))
        assertTrue(quiet.contains("true"))
        assertFalse(quiet.contains("rm -f \"\$0\""))
    }

    @Test
    fun `paths with single quotes are shell-escaped`() {
        val script =
            buildLinuxAppImageUpdateScript(
                newFile = "/tmp/it's.AppImage",
                oldFile = "/opt/App Image/app.AppImage",
                appPid = 1L,
                logFile = "/tmp/o's.log",
                restart = false,
                alreadyReplaced = false,
            )
        assertTrue(script.contains("NEW_FILE='/tmp/it'\\''s.AppImage'"))
        assertTrue(script.contains("OLD_FILE='/opt/App Image/app.AppImage'"))
        assertTrue(script.contains("LOG_FILE='/tmp/o'\\''s.log'"))
    }
}
