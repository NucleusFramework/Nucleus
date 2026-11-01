package dev.nucleusframework.systeminfo

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.systeminfo.model.DiskConnection
import dev.nucleusframework.systeminfo.model.DiskInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mounts a real ext4 image through UDisks2 (no root: polkit lets the active session set up
 * loop devices) and checks that [SystemInfo.disksFlow] reports it, then its removal.
 * Skipped when udisksctl, mkfs.ext4 or the polkit grant is missing (CI containers, SSH).
 */
class LinuxDisksE2ETest {
    private val image = File.createTempFile("nucleus-disks", ".img")
    private var loopDevice: String? = null
    private var mounted = false

    @AfterTest
    fun tearDown() {
        loopDevice?.let { device ->
            if (mounted) run("udisksctl", "unmount", "--no-user-interaction", "-b", device)
            run("udisksctl", "loop-delete", "--no-user-interaction", "-b", device)
        }
        image.delete()
    }

    @Test
    fun `the root file system is classified and identified`() {
        if (Platform.Current != Platform.Linux) return
        val root = SystemInfo.disks().firstOrNull { it.mountPoint == "/" } ?: return
        println("Root: $root")
        // Whatever the machine, / is never reported as a network share or as unknown.
        assertTrue(root.connection != DiskConnection.UNKNOWN && root.connection != DiskConnection.NETWORK, "$root")
        assertTrue(root.totalSpace > 0, "$root")
    }

    @Test
    fun `disksFlow reports a mounted loop image then its removal`() =
        runBlocking {
            if (Platform.Current != Platform.Linux || !prepareImage()) return@runBlocking

            val emissions = Channel<List<DiskInfo>>(Channel.UNLIMITED)
            val collector = launch { SystemInfo.disksFlow().collect { emissions.send(it) } }
            try {
                val initial = withTimeout(TIMEOUT_MS) { emissions.receive() }
                assertTrue(initial.none { it.fileSystem == "ext4" && it.mountPoint.endsWith("/$LABEL") })

                val device =
                    run("udisksctl", "loop-setup", "--no-user-interaction", "-f", image.path)
                        ?.let { Regex("/dev/loop\\d+").find(it)?.value }
                if (device == null) {
                    println("Skipped: udisksctl loop-setup not allowed here")
                    return@runBlocking
                }
                loopDevice = device
                mounted = run("udisksctl", "mount", "--no-user-interaction", "-b", device) != null
                if (!mounted) {
                    println("Skipped: udisksctl mount not allowed here")
                    return@runBlocking
                }

                val volume =
                    withTimeout(TIMEOUT_MS) {
                        var found: DiskInfo? = null
                        while (found == null) found = emissions.receive().firstOrNull { it.name == device }
                        found
                    }
                println("Mounted: $volume")
                assertEquals(DiskConnection.VIRTUAL, volume.connection)
                assertEquals("ext4", volume.fileSystem)
                assertNotNull(volume.volumeId)
                assertTrue(volume.totalSpace > 0)

                run("udisksctl", "unmount", "--no-user-interaction", "-b", device)
                mounted = false
                withTimeout(TIMEOUT_MS) {
                    while (emissions.receive().any { it.name == device }) Unit
                }
            } finally {
                collector.cancel()
            }
        }

    private fun prepareImage(): Boolean {
        val mkfs = listOf("/usr/sbin/mkfs.ext4", "/sbin/mkfs.ext4").firstOrNull { File(it).canExecute() }
        if (mkfs == null || run("udisksctl", "help") == null) {
            println("Skipped: udisksctl or mkfs.ext4 missing")
            return false
        }
        RandomAccessFile(image, "rw").use { it.setLength(IMAGE_SIZE) }
        return run(mkfs, "-q", "-F", "-L", LABEL, image.path) != null
    }

    // The command's output, or null when it failed or does not exist.
    private fun run(vararg command: String): String? =
        runCatching {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
        }.getOrNull()

    private companion object {
        const val LABEL = "nucleusdisks"
        const val IMAGE_SIZE = 32L * 1024 * 1024
        const val TIMEOUT_MS = 10_000L
    }
}
