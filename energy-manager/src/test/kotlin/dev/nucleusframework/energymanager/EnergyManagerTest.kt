package dev.nucleusframework.energymanager

import dev.nucleusframework.energymanager.linux.NativeLinuxEnergyBridge
import dev.nucleusframework.energymanager.macos.NativeMacOsEnergyBridge
import dev.nucleusframework.energymanager.windows.NativeWindowsEnergyBridge
import dev.nucleusframework.energymanager.windows.WindowsEnergyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Platform-specific tests for energy efficiency mode.
 *
 * Linux tests verify nice/ioprio via /proc and ionice.
 * macOS tests verify QoS class via `ps -o nice`.
 * Tests are skipped on platforms that don't match.
 */
class EnergyManagerTest {
    private fun assumeLinux() {
        assumeTrue(
            "Test requires Linux",
            System.getProperty("os.name").lowercase().contains("linux"),
        )
    }

    private fun assumeMacOs() {
        assumeTrue(
            "Test requires macOS",
            System.getProperty("os.name").lowercase().let {
                it.contains("mac") || it.contains("darwin")
            },
        )
    }

    // ── Linux tests ──────────────────────────────────────────────────

    @Test
    @kotlin.test.Ignore("Requires elevated privileges (nice/ioprio) not available on CI runners")
    fun `thread efficiency mode changes nice and ioprio on dedicated thread`() {
        assumeLinux()
        assertTrue(EnergyManager.isAvailable(), "Energy manager not available")

        var niceBefore = 0
        var niceAfter = 0
        var ioBefore = ""
        var ioAfter = ""
        var enableResult = EnergyManager.Result(false)

        val thread =
            Thread {
                val tid = readTid()
                niceBefore = readNice()
                ioBefore = readIoClass(tid)

                enableResult = EnergyManager.enableThreadEfficiencyMode()

                niceAfter = readNice()
                ioAfter = readIoClass(tid)
            }
        thread.start()
        thread.join()

        println("Enable result: $enableResult")
        println("Nice:     $niceBefore -> $niceAfter")
        println("IO class: $ioBefore -> $ioAfter")

        assertTrue(enableResult.success, "Enable failed: ${enableResult.message}")
        assertEquals(0, niceBefore, "Expected initial nice = 0")
        assertEquals(19, niceAfter, "Expected nice = 19 after enable")
        assertTrue(ioAfter.contains("idle", ignoreCase = true), "Expected IO idle, got: $ioAfter")
    }

    @Test
    fun `withEfficiencyMode applies settings inside block`() =
        runBlocking {
            assumeLinux()
            assertTrue(EnergyManager.isAvailable())

            val (nice, ioClass, value) =
                EnergyManager.withEfficiencyMode {
                    val tid = readTid()
                    Triple(readNice(), readIoClass(tid), 42)
                }

            println("Inside withEfficiencyMode: nice=$nice, ioClass=$ioClass")

            assertEquals(19, nice, "Expected nice = 19 inside withEfficiencyMode")
            assertTrue(ioClass.contains("idle", ignoreCase = true), "Expected IO idle, got: $ioClass")
            assertEquals(42, value)
        }

    @Test
    @kotlin.test.Ignore("Requires elevated privileges (nice/ioprio) not available on CI runners")
    fun `thread efficiency mode does not affect other threads`() {
        assumeLinux()
        assertTrue(EnergyManager.isAvailable())

        var efficientNice = -1
        val thread =
            Thread {
                EnergyManager.enableThreadEfficiencyMode()
                efficientNice = readNice()
            }
        thread.start()
        thread.join()

        val mainNice = readNice()

        println("Efficient thread nice: $efficientNice")
        println("Main thread nice:      $mainNice")

        assertEquals(19, efficientNice)
        assertEquals(0, mainNice, "Main thread should not be affected")
    }

    @Test
    fun `linux light efficiency mode sets nice to 10`() {
        assumeLinux()
        assertTrue(EnergyManager.isAvailable())

        val enableResult = EnergyManager.enableLightEfficiencyMode()
        val niceAfter = readNice()
        val disableResult = EnergyManager.disableLightEfficiencyMode()

        println("Light mode: nice after enable = $niceAfter")
        println("Enable result: $enableResult")
        println("Disable result: $disableResult")

        assertTrue(enableResult.success, "Light enable failed: ${enableResult.message}")
        assertEquals(10, niceAfter, "Expected nice = 10 after light enable")
        assertTrue(disableResult.success, "Light disable failed: ${disableResult.message}")
    }

    @Test
    fun `linux light efficiency mode does not change ioprio`() {
        assumeLinux()
        assertTrue(EnergyManager.isAvailable())

        val tid = readTid()
        val ioBefore = readIoClass(tid)
        EnergyManager.enableLightEfficiencyMode()
        val ioAfter = readIoClass(tid)
        EnergyManager.disableLightEfficiencyMode()

        println("IO class: $ioBefore -> $ioAfter")
        assertEquals(ioBefore, ioAfter, "Light mode should not change IO class")
    }

    @Test
    fun `linux light enable disable cycle is idempotent`() {
        assumeLinux()
        assertTrue(EnergyManager.isAvailable())

        assertTrue(EnergyManager.enableLightEfficiencyMode().success)
        assertTrue(EnergyManager.enableLightEfficiencyMode().success)
        assertTrue(EnergyManager.disableLightEfficiencyMode().success)
        assertTrue(EnergyManager.disableLightEfficiencyMode().success)
    }

    @Test
    fun `linux keepAwake system only round trips`() {
        assumeLinux()
        assumeSystemOnlyBackend()

        val enableResult = EnergyManager.keepAwake(AwakeMode.SYSTEM_ONLY)
        println("Linux keepAwake(SYSTEM_ONLY) result: $enableResult, backend=${linuxAwakeBackendName()}")
        assertTrue(enableResult.success, "keepAwake failed: ${enableResult.message}")
        assertTrue(EnergyManager.isAwakeActive(), "Awake request should be active")

        val releaseResult = EnergyManager.releaseAwake()
        assertTrue(releaseResult.success, "releaseAwake failed: ${releaseResult.message}")
        assertTrue(!EnergyManager.isAwakeActive(), "Awake request should be released")
    }

    @Test
    fun `linux keepAwake takes a suspend only GNOME inhibitor in system only mode`() {
        assumeLinux()
        assumeGnomeSessionManager()

        // The flags are read back from gnome-session over D-Bus, not from the
        // value the bridge remembers requesting.
        try {
            EnergyManager.keepAwake(AwakeMode.SYSTEM_AND_DISPLAY)
            val bothFlags = gnomeInhibitorFlags()

            EnergyManager.keepAwake(AwakeMode.SYSTEM_ONLY)
            val systemOnlyFlags = gnomeInhibitorFlags()

            EnergyManager.releaseAwake()
            val releasedFlags = gnomeInhibitorFlags()

            println("GNOME inhibitor flags: both=$bothFlags systemOnly=$systemOnlyFlags released=$releasedFlags")

            assertEquals(
                GNOME_INHIBIT_IDLE or GNOME_INHIBIT_SUSPEND,
                bothFlags,
                "SYSTEM_AND_DISPLAY must inhibit both idle and suspend",
            )
            assertEquals(
                GNOME_INHIBIT_SUSPEND,
                systemOnlyFlags,
                "SYSTEM_ONLY must inhibit suspend only, leaving the screen saver alone",
            )
            assertEquals(null, releasedFlags, "releaseAwake must drop the inhibitor")
        } finally {
            EnergyManager.releaseAwake()
        }
    }

    @Test
    fun `linux keepAwake switches between modes without releasing`() {
        assumeLinux()
        assumeSystemOnlyBackend()

        // Switching modes swaps in a fresh inhibitor, which is acquired before
        // the previous one is dropped.
        try {
            assertTrue(EnergyManager.keepAwake(AwakeMode.SYSTEM_AND_DISPLAY).success)
            assertTrue(EnergyManager.keepAwake(AwakeMode.SYSTEM_ONLY).success)
            assertTrue(EnergyManager.keepAwake(AwakeMode.SYSTEM_AND_DISPLAY).success)
            assertTrue(EnergyManager.isAwakeActive())
        } finally {
            assertTrue(EnergyManager.releaseAwake().success)
        }
        assertTrue(!EnergyManager.isAwakeActive())
    }

    // ── Linux awake helpers ──────────────────────────────────────────

    /** Backend that serves an awake request here, without leaving one held. */
    private fun linuxAwakeBackend(): Int {
        val acquired = EnergyManager.keepAwake(AwakeMode.SYSTEM_AND_DISPLAY).success
        val backend =
            if (acquired) NativeLinuxEnergyBridge.nativeQueryAwakeBackend() else AWAKE_BACKEND_NONE
        EnergyManager.releaseAwake()
        return backend
    }

    private fun linuxAwakeBackendName(): String =
        when (NativeLinuxEnergyBridge.nativeQueryAwakeBackend()) {
            AWAKE_BACKEND_GNOME -> "gnome"
            AWAKE_BACKEND_LOGIND -> "logind"
            AWAKE_BACKEND_X11 -> "x11"
            AWAKE_BACKEND_POWER_MANAGEMENT -> "powermanagement"
            else -> "none"
        }

    /** Skips the test unless an inhibitor that can serve SYSTEM_ONLY is reachable. */
    private fun assumeSystemOnlyBackend() {
        val backend = linuxAwakeBackend()
        assumeTrue("No awake backend reachable in this environment", backend != AWAKE_BACKEND_NONE)
        assumeTrue(
            "Only the X11 screen-saver backend is reachable, which cannot serve SYSTEM_ONLY",
            backend != AWAKE_BACKEND_X11,
        )
    }

    private fun assumeGnomeSessionManager() {
        assumeTrue("Test requires org.gnome.SessionManager", linuxAwakeBackend() == AWAKE_BACKEND_GNOME)
        assumeTrue(
            "Test requires the gdbus CLI",
            gdbus(GNOME_SESSION_PATH, "$GNOME_SESSION_IFACE.GetInhibitors") != null,
        )
    }

    /** Flags of the inhibitor this process holds in gnome-session, or null when it holds none. */
    private fun gnomeInhibitorFlags(): Int? {
        val inhibitors = gdbus(GNOME_SESSION_PATH, "$GNOME_SESSION_IFACE.GetInhibitors") ?: return null
        return INHIBITOR_PATH
            .findAll(inhibitors)
            .map { it.value }
            .firstOrNull { path -> gdbus(path, "$GNOME_INHIBITOR_IFACE.GetAppId")?.contains(AWAKE_APP_ID) == true }
            ?.let { path -> gdbus(path, "$GNOME_INHIBITOR_IFACE.GetFlags") }
            ?.let { flags ->
                UINT32_VALUE
                    .find(flags)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            }
    }

    /** Calls a gnome-session D-Bus method from outside the JVM; null when the call fails. */
    private fun gdbus(
        objectPath: String,
        method: String,
    ): String? =
        runCatching {
            val process =
                ProcessBuilder(
                    "gdbus",
                    "call",
                    "--session",
                    "--dest",
                    GNOME_SESSION_DEST,
                    "--object-path",
                    objectPath,
                    "--method",
                    method,
                ).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) output else null
        }.getOrNull()

    // ── macOS tests ──────────────────────────────────────────────────

    @Test
    fun `macOS isAvailable returns true`() {
        assumeMacOs()
        assertTrue(EnergyManager.isAvailable(), "Energy manager should be available on macOS")
    }

    @Test
    fun `macOS process efficiency mode enable and disable succeed`() {
        assumeMacOs()
        assertTrue(EnergyManager.isAvailable())

        val enableResult = EnergyManager.enableEfficiencyMode()
        println("macOS enable result: $enableResult")
        assertTrue(enableResult.success, "Enable failed: ${enableResult.message}")

        val disableResult = EnergyManager.disableEfficiencyMode()
        println("macOS disable result: $disableResult")
        assertTrue(disableResult.success, "Disable failed: ${disableResult.message}")
    }

    @Test
    fun `macOS process efficiency mode applies PRIO_DARWIN_BG`() {
        assumeMacOs()
        assertTrue(EnergyManager.isAvailable())

        // PRIO_DARWIN_BG operates through a separate kernel flag, not the
        // traditional nice value. We verify the syscalls succeed and that
        // enable/disable form a clean round-trip.
        val enableResult = EnergyManager.enableEfficiencyMode()
        assertTrue(enableResult.success, "Enable failed: ${enableResult.message}")
        assertEquals(0, enableResult.errorCode)

        val disableResult = EnergyManager.disableEfficiencyMode()
        assertTrue(disableResult.success, "Disable failed: ${disableResult.message}")
        assertEquals(0, disableResult.errorCode)
    }

    @Test
    fun `macOS thread efficiency mode enable and disable succeed`() {
        assumeMacOs()
        assertTrue(EnergyManager.isAvailable())

        var enableResult = EnergyManager.Result(false)
        var disableResult = EnergyManager.Result(false)

        val thread =
            Thread {
                enableResult = EnergyManager.enableThreadEfficiencyMode()
                disableResult = EnergyManager.disableThreadEfficiencyMode()
            }
        thread.start()
        thread.join()

        println("macOS thread enable result: $enableResult")
        println("macOS thread disable result: $disableResult")
        assertTrue(enableResult.success, "Thread enable failed: ${enableResult.message}")
        assertTrue(disableResult.success, "Thread disable failed: ${disableResult.message}")
    }

    @Test
    fun `macOS thread efficiency mode does not affect main thread`() {
        assumeMacOs()
        assertTrue(EnergyManager.isAvailable())

        // Thread-level mode uses pthread_set_qos_class_self_np which is
        // per-thread. Verify the call succeeds on a separate thread and
        // that the main thread can still enable/disable independently.
        var threadResult = EnergyManager.Result(false)
        val thread =
            Thread {
                threadResult = EnergyManager.enableThreadEfficiencyMode()
            }
        thread.start()
        thread.join()

        assertTrue(threadResult.success, "Thread enable failed: ${threadResult.message}")

        // Main thread should be unaffected — process-level enable/disable
        // should still work independently
        val enableResult = EnergyManager.enableEfficiencyMode()
        assertTrue(enableResult.success)
        val disableResult = EnergyManager.disableEfficiencyMode()
        assertTrue(disableResult.success)
    }

    @Test
    fun `macOS withEfficiencyMode runs block and returns value`() =
        runBlocking {
            assumeMacOs()
            assertTrue(EnergyManager.isAvailable())

            val result =
                EnergyManager.withEfficiencyMode {
                    42
                }

            assertEquals(42, result)
        }

    @Test
    fun `macOS keepAwake system only round trips`() {
        assumeMacOs()
        assertTrue(EnergyManager.isAvailable())

        val enableResult = EnergyManager.keepAwake(AwakeMode.SYSTEM_ONLY)
        println("macOS keepAwake(SYSTEM_ONLY) result: $enableResult")
        assertTrue(enableResult.success, "keepAwake failed: ${enableResult.message}")
        assertTrue(EnergyManager.isAwakeActive(), "Awake request should be active")

        val releaseResult = EnergyManager.releaseAwake()
        assertTrue(releaseResult.success, "releaseAwake failed: ${releaseResult.message}")
        assertTrue(!EnergyManager.isAwakeActive(), "Awake request should be released")
    }

    @Test
    fun `macOS keepAwake takes the expected IOKit assertion type`() {
        assumeMacOs()
        assertTrue(EnergyManager.isAvailable())

        // The mode is read back from the assertion powerd holds, not from the
        // value the bridge remembers requesting.
        EnergyManager.keepAwake(AwakeMode.SYSTEM_AND_DISPLAY)
        val bothMode = NativeMacOsEnergyBridge.nativeQueryAwakeMode()

        EnergyManager.keepAwake(AwakeMode.SYSTEM_ONLY)
        val systemOnlyMode = NativeMacOsEnergyBridge.nativeQueryAwakeMode()

        EnergyManager.releaseAwake()
        val releasedMode = NativeMacOsEnergyBridge.nativeQueryAwakeMode()

        println("Assertion modes: both=$bothMode systemOnly=$systemOnlyMode released=$releasedMode")

        assertEquals(
            AWAKE_SYSTEM_AND_DISPLAY,
            bothMode,
            "SYSTEM_AND_DISPLAY must map to kIOPMAssertPreventUserIdleDisplaySleep",
        )
        assertEquals(
            AWAKE_SYSTEM_ONLY,
            systemOnlyMode,
            "SYSTEM_ONLY must map to kIOPMAssertPreventUserIdleSystemSleep, leaving display sleep alone",
        )
        assertEquals(AWAKE_NONE, releasedMode, "releaseAwake must drop the assertion")
    }

    @Test
    fun `macOS keepAwake switches between modes without releasing`() {
        assumeMacOs()
        assertTrue(EnergyManager.isAvailable())

        // An assertion's type is immutable, so each switch swaps in a fresh one.
        assertTrue(EnergyManager.keepAwake(AwakeMode.SYSTEM_AND_DISPLAY).success)
        assertTrue(EnergyManager.keepAwake(AwakeMode.SYSTEM_ONLY).success)
        assertTrue(EnergyManager.keepAwake(AwakeMode.SYSTEM_AND_DISPLAY).success)
        assertTrue(EnergyManager.isAwakeActive())
        assertTrue(EnergyManager.releaseAwake().success)
        assertTrue(!EnergyManager.isAwakeActive())
    }

    @Test
    fun `macOS enable disable cycle is idempotent`() {
        assumeMacOs()
        assertTrue(EnergyManager.isAvailable())

        // Double enable should not fail
        assertTrue(EnergyManager.enableEfficiencyMode().success)
        assertTrue(EnergyManager.enableEfficiencyMode().success)

        // Double disable should not fail
        assertTrue(EnergyManager.disableEfficiencyMode().success)
        assertTrue(EnergyManager.disableEfficiencyMode().success)
    }

    // ── Windows tests ────────────────────────────────────────────────

    private fun assumeWindows() {
        assumeTrue(
            "Test requires Windows",
            System.getProperty("os.name").lowercase().contains("windows"),
        )
    }

    @Test
    fun `windows isAvailable returns true`() {
        assumeWindows()
        assertTrue(EnergyManager.isAvailable(), "Energy manager should be available on Windows")
    }

    @Test
    fun `windows process efficiency mode enable and disable succeed`() {
        assumeWindows()
        assertTrue(EnergyManager.isAvailable())

        val enableResult = EnergyManager.enableEfficiencyMode()
        println("Windows enable result: $enableResult")
        assertTrue(enableResult.success, "Enable failed: ${enableResult.message}")

        val disableResult = EnergyManager.disableEfficiencyMode()
        println("Windows disable result: $disableResult")
        assertTrue(disableResult.success, "Disable failed: ${disableResult.message}")
    }

    @Test
    fun `windows thread efficiency mode enable and disable succeed`() {
        assumeWindows()
        assertTrue(EnergyManager.isAvailable())

        var enableResult = EnergyManager.Result(false)
        var disableResult = EnergyManager.Result(false)

        val thread =
            Thread {
                enableResult = EnergyManager.enableThreadEfficiencyMode()
                disableResult = EnergyManager.disableThreadEfficiencyMode()
            }
        thread.start()
        thread.join()

        println("Windows thread enable result: $enableResult")
        println("Windows thread disable result: $disableResult")
        assertTrue(enableResult.success, "Thread enable failed: ${enableResult.message}")
        assertTrue(disableResult.success, "Thread disable failed: ${disableResult.message}")
    }

    @Test
    fun `windows thread efficiency mode does not affect main thread`() {
        assumeWindows()
        assertTrue(EnergyManager.isAvailable())

        var threadResult = EnergyManager.Result(false)
        val thread =
            Thread {
                threadResult = EnergyManager.enableThreadEfficiencyMode()
            }
        thread.start()
        thread.join()

        assertTrue(threadResult.success, "Thread enable failed: ${threadResult.message}")

        // Main thread should be unaffected — process-level enable/disable
        // should still work independently
        val enableResult = EnergyManager.enableEfficiencyMode()
        assertTrue(enableResult.success)
        val disableResult = EnergyManager.disableEfficiencyMode()
        assertTrue(disableResult.success)
    }

    @Test
    fun `windows withEfficiencyMode runs block and returns value`() =
        runBlocking {
            assumeWindows()
            assertTrue(EnergyManager.isAvailable())

            val result =
                EnergyManager.withEfficiencyMode {
                    42
                }

            assertEquals(42, result)
        }

    @Test
    fun `windows enable disable cycle is idempotent`() {
        assumeWindows()
        assertTrue(EnergyManager.isAvailable())

        // Double enable should not fail
        assertTrue(EnergyManager.enableEfficiencyMode().success)
        assertTrue(EnergyManager.enableEfficiencyMode().success)

        // Double disable should not fail
        assertTrue(EnergyManager.disableEfficiencyMode().success)
        assertTrue(EnergyManager.disableEfficiencyMode().success)
    }

    @Test
    fun `windows light efficiency mode enable and disable succeed`() {
        assumeWindows()
        assertTrue(EnergyManager.isAvailable())

        val enableResult = EnergyManager.enableLightEfficiencyMode()
        println("Windows light enable result: $enableResult")
        assertTrue(enableResult.success, "Light enable failed: ${enableResult.message}")

        val disableResult = EnergyManager.disableLightEfficiencyMode()
        println("Windows light disable result: $disableResult")
        assertTrue(disableResult.success, "Light disable failed: ${disableResult.message}")
    }

    @Test
    fun `windows light enable disable cycle is idempotent`() {
        assumeWindows()
        assertTrue(EnergyManager.isAvailable())

        assertTrue(EnergyManager.enableLightEfficiencyMode().success)
        assertTrue(EnergyManager.enableLightEfficiencyMode().success)
        assertTrue(EnergyManager.disableLightEfficiencyMode().success)
        assertTrue(EnergyManager.disableLightEfficiencyMode().success)
    }

    @Test
    fun `windows thread enable disable cycle is idempotent`() {
        assumeWindows()
        assertTrue(EnergyManager.isAvailable())

        var result = true
        val thread =
            Thread {
                result = EnergyManager.enableThreadEfficiencyMode().success &&
                    EnergyManager.enableThreadEfficiencyMode().success &&
                    EnergyManager.disableThreadEfficiencyMode().success &&
                    EnergyManager.disableThreadEfficiencyMode().success
            }
        thread.start()
        thread.join()

        assertTrue(result, "Thread idempotent enable/disable cycle failed")
    }

    @Test
    fun `windows keepAwake system only round trips`() {
        assumeWindows()
        assertTrue(EnergyManager.isAvailable())

        val enableResult = EnergyManager.keepAwake(AwakeMode.SYSTEM_ONLY)
        println("Windows keepAwake(SYSTEM_ONLY) result: $enableResult")
        assertTrue(enableResult.success, "keepAwake failed: ${enableResult.message}")
        assertTrue(EnergyManager.isAwakeActive(), "Awake request should be active")

        val releaseResult = EnergyManager.releaseAwake()
        assertTrue(releaseResult.success, "releaseAwake failed: ${releaseResult.message}")
        assertTrue(!EnergyManager.isAwakeActive(), "Awake request should be released")
    }

    @Test
    fun `windows keepAwake sets the expected execution state flags`() {
        assumeWindows()
        assertTrue(EnergyManager.isAvailable())

        // The execution state is per-thread and all awake requests are routed
        // through the dedicated awake thread, so query it from that thread.
        EnergyManager.keepAwake(AwakeMode.SYSTEM_AND_DISPLAY)
        val bothFlags = queryAwakeFlags()

        EnergyManager.keepAwake(AwakeMode.SYSTEM_ONLY)
        val systemOnlyFlags = queryAwakeFlags()

        EnergyManager.releaseAwake()
        val releasedFlags = queryAwakeFlags()

        println("Flags: both=0x${bothFlags.toHexString()} systemOnly=0x${systemOnlyFlags.toHexString()}")

        assertTrue(bothFlags and ES_SYSTEM_REQUIRED != 0, "SYSTEM_AND_DISPLAY must keep the system awake")
        assertTrue(bothFlags and ES_DISPLAY_REQUIRED != 0, "SYSTEM_AND_DISPLAY must keep the display awake")

        assertTrue(systemOnlyFlags and ES_SYSTEM_REQUIRED != 0, "SYSTEM_ONLY must keep the system awake")
        assertEquals(
            0,
            systemOnlyFlags and ES_DISPLAY_REQUIRED,
            "SYSTEM_ONLY must leave display sleep and the screen saver alone",
        )

        assertEquals(0, releasedFlags and ES_SYSTEM_REQUIRED, "releaseAwake must drop the system request")
        assertEquals(0, releasedFlags and ES_DISPLAY_REQUIRED, "releaseAwake must drop the display request")
    }

    @Test
    fun `windows keepAwake survives the requesting thread dying`() {
        assumeWindows()
        assertTrue(EnergyManager.isAvailable())

        // Regression test: SetThreadExecutionState is per-thread, so a request
        // issued from a short-lived thread (e.g. a Dispatchers.IO worker) used
        // to be silently dropped when that thread exited.
        val thread = Thread { EnergyManager.keepAwake(AwakeMode.SYSTEM_AND_DISPLAY) }
        thread.start()
        thread.join()

        try {
            val flags = queryAwakeFlags()
            assertTrue(
                flags and ES_SYSTEM_REQUIRED != 0,
                "Awake request must survive the requesting thread exiting",
            )
            assertTrue(EnergyManager.isAwakeActive(), "Awake request should still be reported active")
        } finally {
            EnergyManager.releaseAwake()
        }
    }

    @Test
    fun `windows keepAwake switches between modes without releasing`() {
        assumeWindows()
        assertTrue(EnergyManager.isAvailable())

        // SetThreadExecutionState replaces the thread's request, so switching
        // modes back and forth must stay successful and keep the state active.
        assertTrue(EnergyManager.keepAwake(AwakeMode.SYSTEM_AND_DISPLAY).success)
        assertTrue(EnergyManager.keepAwake(AwakeMode.SYSTEM_ONLY).success)
        assertTrue(EnergyManager.keepAwake(AwakeMode.SYSTEM_AND_DISPLAY).success)
        assertTrue(EnergyManager.isAwakeActive())
        assertTrue(EnergyManager.releaseAwake().success)
    }

    /** Reads the execution state flags from the dedicated awake thread. */
    private fun queryAwakeFlags(): Int =
        WindowsEnergyManager.onAwakeThread { NativeWindowsEnergyBridge.nativeQueryAwakeFlags() }

    // ── Linux helpers ────────────────────────────────────────────────

    companion object {
        private const val ES_SYSTEM_REQUIRED = 0x00000001
        private const val ES_DISPLAY_REQUIRED = 0x00000002

        /** Mirrors the AWAKE_* codes of the macOS native bridge. */
        private const val AWAKE_NONE = -1
        private const val AWAKE_SYSTEM_AND_DISPLAY = 0
        private const val AWAKE_SYSTEM_ONLY = 1

        /** Mirrors enum awake_backend in the Linux native bridge. */
        private const val AWAKE_BACKEND_NONE = 0
        private const val AWAKE_BACKEND_GNOME = 1
        private const val AWAKE_BACKEND_LOGIND = 2
        private const val AWAKE_BACKEND_X11 = 3
        private const val AWAKE_BACKEND_POWER_MANAGEMENT = 4

        /** Mirrors GNOME_INHIBIT_* in the Linux native bridge. */
        private const val GNOME_INHIBIT_SUSPEND = 4
        private const val GNOME_INHIBIT_IDLE = 8

        private const val AWAKE_APP_ID = "Nucleus EnergyManager"
        private const val GNOME_SESSION_DEST = "org.gnome.SessionManager"
        private const val GNOME_SESSION_PATH = "/org/gnome/SessionManager"
        private const val GNOME_SESSION_IFACE = "org.gnome.SessionManager"
        private const val GNOME_INHIBITOR_IFACE = "org.gnome.SessionManager.Inhibitor"

        private val INHIBITOR_PATH = Regex("/org/gnome/SessionManager/Inhibitor\\d+")
        private val UINT32_VALUE = Regex("uint32 (\\d+)")

        /**
         * Reads the nice value of the calling thread via /proc/thread-self/stat.
         * Field layout after (comm): state ppid pgrp session tty_nr tpgid flags
         *   minflt cminflt majflt cmajflt utime stime cutime cstime priority nice ...
         * That's index 16 (0-based) after the ") " separator.
         */
        fun readNice(): Int {
            val stat = File("/proc/thread-self/stat").readText()
            val afterComm = stat.substringAfter(") ")
            return afterComm.split(" ")[16].toInt()
        }

        /** Reads the OS thread ID from /proc/thread-self/stat (first field). */
        fun readTid(): Long {
            val stat = File("/proc/thread-self/stat").readText()
            return stat.substringBefore(" ").toLong()
        }

        /** Reads I/O scheduling class via ionice for a given tid. */
        fun readIoClass(tid: Long): String {
            val process =
                ProcessBuilder("ionice", "-p", tid.toString())
                    .redirectErrorStream(true)
                    .start()
            val output =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            process.waitFor()
            return output
        }
    }
}
