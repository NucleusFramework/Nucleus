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
import kotlin.test.assertTrue

/**
 * Mounts a real ISO image through `Mount-DiskImage` (no elevation needed for an ISO) and
 * detaches it through `Dismount-DiskImage`. Checks that [SystemInfo.disksFlow] reports it as a
 * virtual volume with an id, then its removal, and that [SystemInfo.onEjectRequest] neither
 * blocks nor is asked about a forced detach. Refusing a PnP eject needs a real USB drive.
 */
class WindowsDisksE2ETest {
    private val workDir = Files.createTempDirectory("nucleus-disks").toFile()
    private val image = File(workDir, "$LABEL.iso")
    private var mounted = false

    @AfterTest
    fun tearDown() {
        if (mounted) powershell("Dismount-DiskImage -ImagePath '${image.path}' | Out-Null")
        workDir.deleteRecursively()
    }

    @Test
    fun `the system volume is classified and identified`() {
        if (Platform.Current != Platform.Windows) return
        val disks = SystemInfo.disks()
        disks.forEach { println("Disk: $it") }
        val systemDrive = System.getenv("SystemDrive") + "\\"
        val system = disks.first { it.mountPoint.equals(systemDrive, ignoreCase = true) }
        val local = system.connection == DiskConnection.INTERNAL || system.connection == DiskConnection.EXTERNAL
        assertTrue(local, "$system")
        assertTrue(system.totalSpace > 0, "$system")
        // NTFS: the full 64-bit serial, whose low half is what `vol` shows.
        val volumeId = assertNotNull(system.volumeId, "$system")
        assertTrue(volumeId.matches(Regex("[0-9A-F]{16}")), volumeId)
        val vol = run("cmd", "/c", "vol", systemDrive.take(2)).orEmpty()
        val shortSerial = assertNotNull(Regex("[0-9A-F]{4}-[0-9A-F]{4}").find(vol), vol)
        assertTrue(volumeId.endsWith(shortSerial.value.replace("-", "")), "$volumeId vs ${shortSerial.value}")
    }

    @Test
    fun `disksFlow reports a mounted image then its removal`() =
        runBlocking {
            if (Platform.Current != Platform.Windows) return@runBlocking
            createImage()

            val emissions = Channel<List<DiskInfo>>(Channel.UNLIMITED)
            val collector = launch { SystemInfo.disksFlow().collect { emissions.send(it) } }
            try {
                val initial = withTimeout(TIMEOUT_MS) { emissions.receive() }
                assertTrue(initial.none { it.name == LABEL })

                mount()
                val volume = withTimeout(TIMEOUT_MS) { awaitVolume(emissions) }
                println("Mounted: $volume")
                assertEquals(DiskConnection.VIRTUAL, volume.connection)
                assertEquals("UDF", volume.fileSystem)
                assertNotNull(volume.volumeId)
                assertFalse(volume.isRemovable)
                assertTrue(volume.isReadOnly)
                assertTrue(volume.totalSpace > 0)

                assertNotNull(dismount())
                withTimeout(TIMEOUT_MS) {
                    while (emissions.receive().any { it.mountPoint == volume.mountPoint }) Unit
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `a forced image detach is neither asked nor blocked`() {
        if (Platform.Current != Platform.Windows) return
        createImage()
        mount()
        val volume = SystemInfo.disks().first { it.name == LABEL }

        // Images only detach through a forced dismount (a PnP eject is refused with
        // PNP_VetoIllegalDeviceRequest), which no handler can refuse.
        val asked = CopyOnWriteArrayList<DiskInfo>()
        val registration =
            SystemInfo.onEjectRequest {
                asked += it
                false
            }
        try {
            assertNotNull(dismount())
            assertTrue(SystemInfo.disks().none { it.mountPoint == volume.mountPoint })
            assertTrue(asked.none { it.mountPoint == volume.mountPoint }, "$asked")
        } finally {
            registration.close()
        }
    }

    /**
     * Needs a real USB drive, named by `NUCLEUS_E2E_EJECT_DRIVE` (`E:`), and ejects it at the
     * end: virtual disks cannot be PnP-ejected, so nothing on CI can take this path.
     */
    @Test
    fun `an eject request handler can refuse a PnP eject`() {
        if (Platform.Current != Platform.Windows) return
        val drive = System.getenv("NUCLEUS_E2E_EJECT_DRIVE")?.trimEnd('\\', ':') ?: return
        val mountPoint = "$drive:\\"
        val volume = SystemInfo.disks().first { it.mountPoint.equals(mountPoint, ignoreCase = true) }
        println("Ejecting: $volume")
        assertEquals(DiskConnection.EXTERNAL, volume.connection)
        assertTrue(volume.isRemovable)

        val asked = CopyOnWriteArrayList<DiskInfo>()
        val refusing =
            SystemInfo.onEjectRequest {
                asked += it
                it.mountPoint != volume.mountPoint
            }
        try {
            val vetoed = assertNotNull(pnpEject(drive))
            assertTrue("veto=" in vetoed && "cr=0" !in vetoed, vetoed)
            assertTrue(SystemInfo.disks().any { it.mountPoint == volume.mountPoint })
            assertEquals(volume.volumeId, asked.first { it.mountPoint == volume.mountPoint }.volumeId)
        } finally {
            refusing.close()
        }

        // An allowing handler must not leave our own handle in the way.
        asked.clear()
        val allowing =
            SystemInfo.onEjectRequest {
                asked += it
                true
            }
        try {
            assertEquals("cr=0", assertNotNull(pnpEject(drive)).trim().substringBefore(' '))
            assertTrue(asked.any { it.mountPoint == volume.mountPoint }, "$asked")
            assertTrue(SystemInfo.disks().none { it.mountPoint == volume.mountPoint })
        } finally {
            allowing.close()
        }
    }

    // CM_Request_Device_Eject on the drive's disk, what "Safely Remove Hardware" does. Returns
    // "cr=<CONFIGRET> veto=<PNP_VETO_TYPE> <vetoing device>".
    private fun pnpEject(drive: String): String? =
        powershell(
            """
            Add-Type -TypeDefinition @'
            using System; using System.Text; using System.Runtime.InteropServices;
            public static class PnpEject {
              [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
              static extern int CM_Locate_DevNodeW(out uint devinst, string id, int flags);
              [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
              static extern int CM_Request_Device_EjectW(uint devinst, out int veto, StringBuilder name, int length, int flags);
              public static string Eject(string id) {
                uint devinst; int veto; var name = new StringBuilder(260);
                int cr = CM_Locate_DevNodeW(out devinst, id, 0);
                if (cr == 0) cr = CM_Request_Device_EjectW(devinst, out veto, name, 260, 0); else veto = -1;
                return "cr=" + cr + " veto=" + veto + " " + name;
              }
            }
            '@
            ${'$'}number = (Get-Partition -DriveLetter '$drive').DiskNumber
            ${'$'}disk = Get-CimInstance Win32_DiskDrive | Where-Object Index -eq ${'$'}number
            [PnpEject]::Eject(${'$'}disk.PNPDeviceID)
            """.trimIndent(),
        )

    // A UDF image with one file, written through IMAPI2 (no admin, no external tool).
    private fun createImage() {
        val script =
            """
            ${'$'}src = Join-Path '${workDir.path}' 'src'
            New-Item -ItemType Directory ${'$'}src | Out-Null
            Set-Content (Join-Path ${'$'}src 'hello.txt') 'nucleus'
            ${'$'}fsi = New-Object -ComObject IMAPI2FS.MsftFileSystemImage
            ${'$'}fsi.FileSystemsToCreate = 4
            ${'$'}fsi.VolumeName = '$LABEL'
            ${'$'}fsi.Root.AddTree(${'$'}src, ${'$'}false)
            ${'$'}result = ${'$'}fsi.CreateResultImage()
            ${'$'}cp = New-Object CodeDom.Compiler.CompilerParameters
            ${'$'}cp.CompilerOptions = '/unsafe'
            Add-Type -CompilerParameters ${'$'}cp -TypeDefinition @'
            public class IsoWriter {
              public unsafe static void Write(string path, object stream, int block, int blocks) {
                int read = 0; byte[] buf = new byte[block];
                var output = System.IO.File.OpenWrite(path);
                var input = (System.Runtime.InteropServices.ComTypes.IStream)stream;
                while (blocks-- > 0) { input.Read(buf, block, (System.IntPtr)(&read)); output.Write(buf, 0, read); }
                output.Close();
              }
            }
            '@
            [IsoWriter]::Write('${image.path}', ${'$'}result.ImageStream, ${'$'}result.BlockSize, ${'$'}result.TotalBlocks)
            """.trimIndent()
        assertNotNull(powershell(script))
        assertTrue(image.length() > 0)
    }

    private fun mount() {
        assertNotNull(powershell("Mount-DiskImage -ImagePath '${image.path}' | Out-Null"))
        mounted = true
        // The drive letter is assigned shortly after the image attaches.
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (SystemInfo.disks().none { it.name == LABEL }) {
            assertTrue(System.currentTimeMillis() < deadline, "the image never got a drive letter")
            Thread.sleep(100)
        }
    }

    private fun dismount(): String? =
        powershell(
            "Dismount-DiskImage -ImagePath '${image.path}' -ErrorAction Stop | Out-Null",
        )?.also { mounted = false }

    private suspend fun awaitVolume(emissions: Channel<List<DiskInfo>>): DiskInfo {
        while (true) emissions.receive().firstOrNull { it.name == LABEL }?.let { return it }
    }

    private fun powershell(script: String): String? {
        val file = File(workDir, "script-${System.nanoTime()}.ps1")
        // A BOM, so Windows PowerShell 5.1 reads the script as UTF-8.
        file.writeText("\uFEFF" + script)
        return run("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", file.path)
    }

    // The command's output, or null when it failed.
    private fun run(vararg command: String): String? =
        runCatching {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) {
                output
            } else {
                println("${command.joinToString(" ")} failed: $output")
                null
            }
        }.getOrNull()

    private companion object {
        const val LABEL = "nucleusdisks"
        const val TIMEOUT_MS = 15_000L
    }
}
