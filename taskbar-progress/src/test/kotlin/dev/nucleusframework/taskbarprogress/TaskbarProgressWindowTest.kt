package dev.nucleusframework.taskbarprogress

import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskbarProgressWindowTest {
    private var frame: Frame? = null

    @AfterTest
    fun tearDown() {
        frame?.let { window ->
            if (TaskbarProgress.isAvailable()) {
                TaskbarProgress.hideProgress(window)
                TaskbarProgress.stopAttention(window)
            }
            window.dispose()
        }
    }

    @Test
    fun `window and app-wide dock apis succeed on macos`() {
        if (GraphicsEnvironment.isHeadless()) return
        if (!TaskbarProgress.isAvailable()) return
        assertTrue(TaskbarProgress.isAvailable())
        val window = Frame("kover-taskbar").also { frame = it }
        window.setSize(80, 60)
        window.isUndecorated = true
        window.isVisible = true

        assertTrue(TaskbarProgress.setProgress(window, 0.25))
        assertTrue(TaskbarProgress.setProgress(window, 1.5))
        assertTrue(TaskbarProgress.setProgress(window, -0.2))
        assertTrue(TaskbarProgress.setState(window, TaskbarProgress.State.NORMAL))
        assertTrue(TaskbarProgress.setState(window, TaskbarProgress.State.PAUSED))
        assertTrue(TaskbarProgress.setState(window, TaskbarProgress.State.ERROR))
        assertTrue(TaskbarProgress.setState(window, TaskbarProgress.State.INDETERMINATE))
        assertTrue(TaskbarProgress.setState(window, TaskbarProgress.State.NO_PROGRESS))
        assertTrue(TaskbarProgress.showProgress(window, 0.4))
        assertTrue(TaskbarProgress.showError(window, 0.8))
        assertTrue(TaskbarProgress.showPaused(window, 0.3))
        assertTrue(TaskbarProgress.showIndeterminate(window))
        assertTrue(TaskbarProgress.hideProgress(window))
        assertTrue(TaskbarProgress.requestAttention(window, TaskbarProgress.AttentionType.INFORMATIONAL))
        assertTrue(TaskbarProgress.stopAttention(window))
        assertTrue(TaskbarProgress.requestAttention(window, TaskbarProgress.AttentionType.CRITICAL))
        assertTrue(TaskbarProgress.stopAttention(window))
    }

    @Test
    fun `unavailable hwnd path still returns a boolean`() {
        if (GraphicsEnvironment.isHeadless()) return
        if (!TaskbarProgress.isAvailable()) {
            val window = Frame("kover-taskbar-missing").also { frame = it }
            assertFalse(TaskbarProgress.setProgress(window, 0.1))
            assertFalse(TaskbarProgress.setState(window, TaskbarProgress.State.NORMAL))
        }
    }
}
