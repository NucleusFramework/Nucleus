package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.scheduler.InternalSchedulerApi
import dev.nucleusframework.scheduler.TaskId
import dev.nucleusframework.scheduler.TaskRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@OptIn(InternalSchedulerApi::class)
class PlatformSchedulerFallbackTest {
    @Test
    fun `windows availability requires the Windows platform and a loaded library`() {
        // macOS and Windows share the `nucleus_scheduler` library name, so the JNI
        // class can report loaded on macOS even though the Windows COM entry points
        // are absent. Availability must also require Platform.Windows.
        if (Platform.Current == Platform.Windows) {
            assertEquals(WindowsTaskSchedulerJni.isLoaded, WindowsTaskScheduler.isAvailable)
        } else {
            assertFalse(WindowsTaskScheduler.isAvailable)
        }
    }

    @Test
    fun `linux JNI is only loaded on Linux`() {
        if (Platform.Current == Platform.Linux) {
            assertEquals(LinuxSystemdSchedulerJni.isLoaded, LinuxSystemdScheduler.isAvailable)
        } else {
            assertFalse(LinuxSystemdSchedulerJni.isLoaded)
            assertFalse(LinuxSystemdScheduler.isAvailable)
        }
    }

    @Test
    fun `windows scheduler refuses to enqueue when the native library is missing`() {
        if (WindowsTaskScheduler.isAvailable) return
        val request = TaskRequest.periodic(TaskId("win-fallback"), 1.hours)
        assertFalse(WindowsTaskScheduler.enqueue(request))
        assertFalse(WindowsTaskScheduler.cancel(TaskId("win-fallback")))
        assertFalse(WindowsTaskScheduler.isScheduled(TaskId("win-fallback")))
        WindowsTaskScheduler.cancelAll()
        assertTrue(WindowsTaskScheduler.getAllTasks().isEmpty())
    }

    @Test
    fun `linux scheduler refuses to enqueue when the native library is missing`() {
        if (LinuxSystemdScheduler.isAvailable) return
        val request = TaskRequest.periodic(TaskId("linux-fallback"), 1.hours)
        assertFalse(LinuxSystemdScheduler.enqueue(request))
        assertFalse(LinuxSystemdScheduler.cancel(TaskId("linux-fallback")))
        assertFalse(LinuxSystemdScheduler.isScheduled(TaskId("linux-fallback")))
        LinuxSystemdScheduler.cancelAll()
        assertTrue(LinuxSystemdScheduler.getAllTasks().isEmpty())
    }

    @Test
    fun `windows and linux retry helpers refuse when the native library is missing`() {
        if (!WindowsTaskScheduler.isAvailable) {
            assertFalse(WindowsTaskScheduler.scheduleRetry(TaskId("win-retry"), 30))
            assertNull(WindowsTaskScheduler.getTaskInfo(TaskId("win-retry")))
        }
        if (!LinuxSystemdScheduler.isAvailable) {
            assertFalse(LinuxSystemdScheduler.scheduleRetry(TaskId("linux-retry"), 30))
            assertNull(LinuxSystemdScheduler.getTaskInfo(TaskId("linux-retry")))
        }
    }

    @Test
    fun `linux unit names embed the task id`() {
        val name = LinuxSystemdScheduler.unitBaseName(TaskId("nightly"))
        assertTrue(name.startsWith("nucleus-"))
        assertTrue(name.endsWith("-nightly"))
        assertFalse(name.contains(" "))
    }

    @Test
    fun `windows day-of-week bits match Task Scheduler constants`() {
        assertEquals(0x01, WindowsTaskSchedulerJni.SUNDAY)
        assertEquals(0x02, WindowsTaskSchedulerJni.MONDAY)
        assertEquals(0x04, WindowsTaskSchedulerJni.TUESDAY)
        assertEquals(0x08, WindowsTaskSchedulerJni.WEDNESDAY)
        assertEquals(0x10, WindowsTaskSchedulerJni.THURSDAY)
        assertEquals(0x20, WindowsTaskSchedulerJni.FRIDAY)
        assertEquals(0x40, WindowsTaskSchedulerJni.SATURDAY)
        assertEquals(1, WindowsTaskSchedulerJni.TASK_STATE_DISABLED)
        assertEquals(2, WindowsTaskSchedulerJni.TASK_STATE_QUEUED)
        assertEquals(3, WindowsTaskSchedulerJni.TASK_STATE_READY)
        assertEquals(4, WindowsTaskSchedulerJni.TASK_STATE_RUNNING)
    }
}
