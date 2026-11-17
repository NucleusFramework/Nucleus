package dev.nucleusframework.tray.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AwaitAnchoredWindowPositionTest {
    @Test
    fun `skips origin placeholder and waits for a stable tray-anchored position`() =
        runBlocking {
            val frames =
                ArrayDeque(
                    listOf(
                        WindowPosition.PlatformDefault,
                        WindowPosition.Absolute(0.dp, 0.dp),
                        WindowPosition.Absolute(0.dp, 25.dp),
                        WindowPosition.Absolute(1_280.dp, 25.dp),
                        WindowPosition.Absolute(1_280.dp, 25.dp),
                    ),
                )
            val workArea = ScreenRect(x = 0, y = 25, width = 1_440, height = 875)
            val pos =
                awaitAnchoredWindowPosition(
                    timeoutMs = 2_000,
                    pollDelayMs = 1,
                    isUsable = { isUsableAnchorPosition(it, workArea) },
                ) {
                    if (frames.isEmpty()) {
                        WindowPosition.Absolute(1_280.dp, 25.dp)
                    } else {
                        frames.removeFirst()
                    }
                }

            val absolute = assertIs<WindowPosition.Absolute>(pos)
            assertEquals(1_280f, absolute.x.value)
            assertEquals(25f, absolute.y.value)
        }

    @Test
    fun `does not latch the first unlaid-out mac status item coordinate`() {
        val screen = ScreenRect(x = 0, y = 0, width = 1_440, height = 900)
        assertFalse(isLaidOutMacStatusItem(x = 0, y = 0, screen = screen))
        assertFalse(isLaidOutMacStatusItem(x = 11, y = 0, screen = screen))
        assertTrue(isLaidOutMacStatusItem(x = 1_380, y = 0, screen = screen))
        val leftDisplay = ScreenRect(x = -1_920, y = 0, width = 1_920, height = 1_080)
        assertTrue(isLaidOutMacStatusItem(x = -200, y = 0, screen = leftDisplay))
        assertFalse(isLaidOutMacStatusItem(x = -1_920, y = 0, screen = leftDisplay))
    }

    @Test
    fun `work-area origin window is not a usable mac tray anchor`() {
        val workArea = ScreenRect(x = 0, y = 25, width = 1_440, height = 875)
        assertFalse(isUsableAnchorPosition(WindowPosition.PlatformDefault, workArea))
        assertFalse(isUsableAnchorPosition(WindowPosition.Absolute(0.dp, 0.dp), workArea))
        assertFalse(isUsableAnchorPosition(WindowPosition.Absolute(0.dp, 25.dp), workArea))
        assertTrue(isUsableAnchorPosition(WindowPosition.Absolute(1_100.dp, 25.dp), workArea))
    }
}
