package dev.nucleusframework.updater

import dev.nucleusframework.core.runtime.VersionedInstall
import dev.nucleusframework.updater.internal.InstalledVersionWatcher
import dev.nucleusframework.updater.internal.WindowsHotUpdate
import dev.nucleusframework.updater.internal.windowsCommandLine
import dev.nucleusframework.updater.internal.windowsRelaunchCommand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class WindowsHotUpdateMultiInstanceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun install(): VersionedInstall {
        val root = tmp.newFolder("App")
        val current = File(root, "versions/1.0.0").apply { File(this, "runtime").mkdirs() }
        val launcher = File(root, "App.exe").apply { writeText("launcher") }
        pointCfgAt(root, "1.0.0")
        return VersionedInstall(root, current, launcher)
    }

    private fun pointCfgAt(
        root: File,
        version: String,
    ) {
        File(root, "app").mkdirs()
        File(root, "app/App.cfg").writeText("[Application]\r\napp.runtime=\$ROOTDIR\\versions\\$version\\runtime\r\n")
    }

    @Test
    fun `command line splits back into the same arguments`() {
        assertEquals("plain", windowsCommandLine(listOf("plain")))
        assertEquals("\"C:\\My Docs\\a.txt\"", windowsCommandLine(listOf("C:\\My Docs\\a.txt")))
        assertEquals("\"say \\\"hi\\\"\"", windowsCommandLine(listOf("say \"hi\"")))
        // Trailing backslashes must not escape the closing quote.
        assertEquals("\"C:\\My Dir\\\\\"", windowsCommandLine(listOf("C:\\My Dir\\")))
        assertEquals("\"\" two", windowsCommandLine(listOf("", "two")))
    }

    @Test
    fun `classic relaunch passes the arguments as one quoted command line`() {
        val command = windowsRelaunchCommand(true, "C:\\App\\App.exe", listOf("C:\\it's here\\doc.txt"))

        assertTrue(command.contains("Start-Process 'C:\\App\\App.exe' -ArgumentList '\"C:\\it''s here\\doc.txt\"'"))
        assertEquals(
            "\n# Relaunch the application\nStart-Process 'C:\\App\\App.exe'",
            windowsRelaunchCommand(true, "C:\\App\\App.exe"),
        )
    }

    @Test
    fun `a reader waits for an install in progress`() {
        val install = install()
        val installing = CountDownLatch(1)
        val order = mutableListOf<String>()
        val installer =
            thread {
                WindowsHotUpdate.withInstallLock(install) {
                    installing.countDown()
                    Thread.sleep(400)
                    synchronized(order) { order += "install done" }
                }
            }
        installing.await(5, TimeUnit.SECONDS)

        WindowsHotUpdate.withInstallLock(install, shared = true) { synchronized(order) { order += "read" } }
        installer.join()

        assertEquals(listOf("install done", "read"), order)
    }

    @Test
    fun `watcher publishes a version another process installed`() {
        val install = install()
        val watcher = InstalledVersionWatcher(install).apply { start() }
        assertNull(watcher.version.value)

        // What the other instance's installer leaves behind: the new version, then the cfg.
        File(install.versionsDir, "1.1.0/runtime").mkdirs()
        Thread.sleep(200) // let the watch service register before the change
        pointCfgAt(install.root, "1.1.0")

        val seen = runBlocking { withTimeout(10_000) { watcher.version.first { it != null } } }
        assertEquals("1.1.0", seen)
    }
}
