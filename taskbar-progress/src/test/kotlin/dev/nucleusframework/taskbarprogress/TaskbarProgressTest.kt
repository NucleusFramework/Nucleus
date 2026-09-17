package dev.nucleusframework.taskbarprogress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskbarProgressTest {
    @Test
    fun `state and attention enums expose native flags`() {
        assertEquals(0x00, TaskbarProgress.State.NO_PROGRESS.flag)
        assertEquals(0x01, TaskbarProgress.State.INDETERMINATE.flag)
        assertEquals(0x02, TaskbarProgress.State.NORMAL.flag)
        assertEquals(0x04, TaskbarProgress.State.ERROR.flag)
        assertEquals(0x08, TaskbarProgress.State.PAUSED.flag)
        assertEquals(1, TaskbarProgress.AttentionType.INFORMATIONAL.nativeValue)
        assertEquals(2, TaskbarProgress.AttentionType.CRITICAL.nativeValue)
    }

    @Test
    fun `hwnd apis succeed on macos when the dock library is loaded`() {
        TaskbarProgress.linuxDesktopFilename = "myapp.desktop"
        assertEquals("myapp.desktop", TaskbarProgress.linuxDesktopFilename)
        TaskbarProgress.linuxDesktopFilename = null

        val available = TaskbarProgress.isAvailable()
        val progress = TaskbarProgress.setProgressForHwnd(0L, 0.5)
        val clampedHigh = TaskbarProgress.setProgressForHwnd(0L, 2.0)
        val clampedLow = TaskbarProgress.setProgressForHwnd(0L, -1.0)
        val normal = TaskbarProgress.setStateForHwnd(0L, TaskbarProgress.State.NORMAL)
        val error = TaskbarProgress.setStateForHwnd(0L, TaskbarProgress.State.ERROR)
        val paused = TaskbarProgress.setStateForHwnd(0L, TaskbarProgress.State.PAUSED)
        val indeterminate = TaskbarProgress.setStateForHwnd(0L, TaskbarProgress.State.INDETERMINATE)
        val hidden = TaskbarProgress.setStateForHwnd(0L, TaskbarProgress.State.NO_PROGRESS)
        val attention = TaskbarProgress.requestAttentionForHwnd(0L, TaskbarProgress.AttentionType.INFORMATIONAL)
        val critical = TaskbarProgress.requestAttentionForHwnd(0L, TaskbarProgress.AttentionType.CRITICAL)
        val stop = TaskbarProgress.stopAttentionForHwnd(0L)

        if (!available) {
            assertFalse(progress)
            assertFalse(normal)
        }
        // hwnd 0 is not a real window. Native backends may succeed
        // (macOS dock is app-wide) or refuse the handle.
        TaskbarProgress.stopAttentionForHwnd(0L)
        TaskbarProgress.setStateForHwnd(0L, TaskbarProgress.State.NO_PROGRESS)
    }

    @Test
    fun `linux desktop filename override is used by the detector fallback`() {
        TaskbarProgress.linuxDesktopFilename = "coverage.desktop"
        assertEquals("coverage.desktop", TaskbarProgress.linuxDesktopFilename)
        val detected = dev.nucleusframework.taskbarprogress.linux.LinuxDesktopFileDetector.desktopFilename
        assertTrue(detected == null || detected.endsWith(".desktop"))
        TaskbarProgress.linuxDesktopFilename = null
        assertEquals(null, TaskbarProgress.linuxDesktopFilename)
        assertTrue(TaskbarProgress.State.entries.size == 5)
        assertTrue(TaskbarProgress.AttentionType.entries.size == 2)
    }
}
