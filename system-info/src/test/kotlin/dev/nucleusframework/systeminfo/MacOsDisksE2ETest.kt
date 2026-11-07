package dev.nucleusframework.systeminfo

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.systeminfo.model.DiskConnection
import dev.nucleusframework.systeminfo.model.DiskInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Attaches a real disk image through `hdiutil` and ejects it through `diskutil` (no root needed).
 * Checks that [SystemInfo.disksFlow] reports it as a virtual volume with a UUID, then its removal,
 * and that [SystemInfo.onEjectRequest] can refuse its unmount.
 */
class MacOsDisksE2ETest {
    private val workDir = Files.createTempDirectory("nucleus-disks").toFile()
    private val image = File(workDir, "$LABEL.dmg")
    private var device: String? = null

    @AfterTest
    fun tearDown() {
        device?.let { run("diskutil", "eject", "force", it) }
        workDir.deleteRecursively()
    }

    @Test
    fun `the root volume is classified and identified`() {
        if (Platform.Current != Platform.MacOS) return
        val disks = SystemInfo.disks()
        disks.forEach { println("Disk: $it") }
        val root = disks.first { it.mountPoint == "/" }
        // Booted from an internal or an external disk, never from an image or a share.
        assertTrue(root.connection == DiskConnection.INTERNAL || root.connection == DiskConnection.EXTERNAL, "$root")
        assertNotNull(root.volumeId, "$root")
        assertTrue(root.totalSpace > 0, "$root")
        // Hidden system volumes (VM, Preboot, the Data volume behind /) are left out.
        assertTrue(disks.none { it.mountPoint.startsWith("/System/Volumes/") }, "$disks")
    }

    @Test
    fun `disksFlow reports an attached image then its removal`() =
        runBlocking {
            if (Platform.Current != Platform.MacOS) return@runBlocking
            createImage()

            val emissions = Channel<List<DiskInfo>>(Channel.UNLIMITED)
            val collector = launch { SystemInfo.disksFlow().collect { emissions.send(it) } }
            try {
                val initial = withTimeout(TIMEOUT_MS) { emissions.receive() }
                assertTrue(initial.none { it.mountPoint == MOUNT_POINT })

                attach()
                val volume = withTimeout(TIMEOUT_MS) { awaitVolume(emissions) }
                println("Attached: $volume")
                assertEquals(DiskConnection.VIRTUAL, volume.connection)
                assertTrue(volume.name.startsWith(device!!), "$volume")
                assertNotNull(volume.volumeId)
                assertFalse(volume.isRemovable)
                assertTrue(volume.totalSpace > 0)

                assertNotNull(run("diskutil", "eject", device!!))
                device = null
                withTimeout(TIMEOUT_MS) {
                    while (emissions.receive().any { it.mountPoint == MOUNT_POINT }) Unit
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `an eject request handler can refuse the unmount`() {
        if (Platform.Current != Platform.MacOS) return
        createImage()
        attach()
        val volumeId = SystemInfo.disks().first { it.mountPoint == MOUNT_POINT }.volumeId

        val asked = CopyOnWriteArrayList<DiskInfo>()
        val registration =
            SystemInfo.onEjectRequest { disk ->
                asked += disk
                disk.mountPoint != MOUNT_POINT
            }
        try {
            // diskutil goes through DiskArbitration and gets the dissent.
            assertNull(run("diskutil", "eject", device!!), "the eject should have been refused")
            assertTrue(SystemInfo.disks().any { it.mountPoint == MOUNT_POINT })
            val request = asked.first { it.mountPoint == MOUNT_POINT }
            assertEquals(volumeId, request.volumeId)
            assertEquals(DiskConnection.VIRTUAL, request.connection)
        } finally {
            registration.close()
        }

        // With the handler gone, nothing holds the volume any more.
        assertNotNull(run("diskutil", "eject", device!!))
        device = null
    }

    private fun createImage() {
        assertNotNull(
            run("hdiutil", "create", "-size", "32m", "-fs", "HFS+", "-volname", LABEL, "-layout", "NONE", image.path),
        )
    }

    private fun attach() {
        // -nobrowse would mark the mount MNT_DONTBROWSE, which disks() hides.
        val output = assertNotNull(run("hdiutil", "attach", "-noverify", "-noautoopen", image.path))
        device = Regex("/dev/disk\\d+").find(output)?.value
        assertNotNull(device, output)
    }

    private suspend fun awaitVolume(emissions: Channel<List<DiskInfo>>): DiskInfo {
        while (true) emissions.receive().firstOrNull { it.mountPoint == MOUNT_POINT }?.let { return it }
    }

    // The command's output, or null when it failed.
    private fun run(vararg command: String): String? =
        runCatching {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) {
                output
            } else {
                println("${command.joinToString(" ")} failed: $output")
                null
            }
        }.getOrNull()

    private companion object {
        const val LABEL = "nucleusdisks"
        const val MOUNT_POINT = "/Volumes/$LABEL"
        const val TIMEOUT_MS = 15_000L
    }
}
