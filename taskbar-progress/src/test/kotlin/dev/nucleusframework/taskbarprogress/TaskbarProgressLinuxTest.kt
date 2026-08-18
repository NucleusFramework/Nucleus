package dev.nucleusframework.taskbarprogress

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class TaskbarProgressLinuxTest {
    @AfterTest
    fun tearDown() {
        TaskbarProgress.linuxDesktopFilename = null
        TaskbarProgress.setStateForHwnd(0L, TaskbarProgress.State.NO_PROGRESS)
        TaskbarProgress.stopAttentionForHwnd(0L)
    }

    @Test
    fun `linux progress and attention apis run once a desktop file id is set`() {
        if (!System.getProperty("os.name").contains("Linux", ignoreCase = true)) return
        TaskbarProgress.linuxDesktopFilename = "nucleus-kover-coverage.desktop"
        if (!TaskbarProgress.isAvailable()) return

        TaskbarProgress.setProgressForHwnd(0L, 0.5)
        TaskbarProgress.setProgressForHwnd(0L, 2.0)
        TaskbarProgress.setProgressForHwnd(0L, -1.0)
        TaskbarProgress.setStateForHwnd(0L, TaskbarProgress.State.NORMAL)
        TaskbarProgress.setStateForHwnd(0L, TaskbarProgress.State.ERROR)
        TaskbarProgress.setStateForHwnd(0L, TaskbarProgress.State.PAUSED)
        TaskbarProgress.setStateForHwnd(0L, TaskbarProgress.State.INDETERMINATE)
        TaskbarProgress.setStateForHwnd(0L, TaskbarProgress.State.NO_PROGRESS)
        TaskbarProgress.requestAttentionForHwnd(0L, TaskbarProgress.AttentionType.INFORMATIONAL)
        TaskbarProgress.requestAttentionForHwnd(0L, TaskbarProgress.AttentionType.CRITICAL)
        TaskbarProgress.stopAttentionForHwnd(0L)
        assertTrue(TaskbarProgress.isAvailable())
    }
}
