package dev.nucleusframework.window.tao.popup

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit cases for the #569 screen clamp: the geometry decision the headful
 * suite then verifies against real windows.
 *
 * Coordinates are physical pixels. The fixtures use a 1920×1080 primary
 * display with a 40 px taskbar (work area `0,0 → 1920,1040`) and, where
 * relevant, a second display to its right.
 */
class PopupScreenClampTest {
    // ── No geometry / degenerate input: the pre-#569 behaviour ─────────────

    @Test
    fun `no geometry leaves the frame untouched`() {
        assertEquals(
            IntOffset.Zero,
            popupScreenClampOffset(rect(0, 0, 200, 300), geometry = null),
        )
    }

    @Test
    fun `an empty frame is never moved`() {
        // A layer pushes frames before Compose has measured the content.
        assertEquals(IntOffset.Zero, clampAt(windowAt(1900, 1000), rect(0, 0, 0, 0)))
        assertEquals(IntOffset.Zero, clampAt(windowAt(1900, 1000), rect(0, 0, 200, 0)))
    }

    @Test
    fun `no usable work area leaves the frame untouched`() {
        val geometry =
            PopupScreenGeometry(
                parentContentOriginPx = IntOffset(1900, 1000),
                workAreasPx = listOf(IntRect(0, 0, 0, 0)),
            )
        assertEquals(IntOffset.Zero, popupScreenClampOffset(rect(0, 0, 200, 300), geometry))
    }

    // ── The regression the issue reports ──────────────────────────────────

    @Test
    fun `a popup already inside the work area is not moved`() {
        // Window at the middle of the screen, dropdown just below its anchor.
        assertEquals(IntOffset.Zero, clampAt(windowAt(400, 300), rect(50, 120, 200, 180)))
    }

    @Test
    fun `a dropdown past the bottom edge slides up instead of landing offscreen`() {
        // Window content origin 100 px above the taskbar; Compose believes it
        // has `workAreaHeight` of room below the anchor, so it does not flip.
        val clamp = clampAt(windowAt(400, 940), rect(0, 20, 200, 300))
        // 940 + 20 + 300 = 1260, work area bottom is 1040 → back by 220.
        assertEquals(IntOffset(0, -220), clamp)
        assertTrue(screenRect(windowAt(400, 940), rect(0, 20, 200, 300), clamp) in PRIMARY_WORK)
    }

    @Test
    fun `a menu past the right edge slides left`() {
        val clamp = clampAt(windowAt(1700, 200), rect(100, 0, 300, 200))
        // 1700 + 100 + 300 = 2100, work area right is 1920 → back by 180.
        assertEquals(IntOffset(-180, 0), clamp)
    }

    @Test
    fun `both axes clamp independently`() {
        val clamp = clampAt(windowAt(1800, 1000), rect(60, 60, 400, 400))
        assertEquals(IntOffset(1920 - 400 - 1860, 1040 - 400 - 1060), clamp)
        assertTrue(screenRect(windowAt(1800, 1000), rect(60, 60, 400, 400), clamp) in PRIMARY_WORK)
    }

    @Test
    fun `a popup extending above the window origin is not pinned at zero`() {
        // The other half of #569: Compose clamps to 0 in *window* coordinates,
        // so a popup that should open upward gets stuck at the window's top
        // edge. In screen space there is room, so the clamp must not move it.
        assertEquals(IntOffset.Zero, clampAt(windowAt(600, 500), rect(0, -220, 200, 180)))
    }

    @Test
    fun `a popup above the screen top slides down`() {
        // Same shape, but the window itself is at the top: now it really is
        // offscreen and must come back in.
        val clamp = clampAt(windowAt(600, 30), rect(0, -220, 200, 180))
        assertEquals(IntOffset(0, 190), clamp)
        assertEquals(0, screenRect(windowAt(600, 30), rect(0, -220, 200, 180), clamp).top)
    }

    @Test
    fun `the taskbar is respected, not just the screen bounds`() {
        // 1040..1080 is the taskbar. A frame ending at 1060 must come back to
        // 1040 even though it is inside the monitor's full bounds.
        val clamp = clampAt(windowAt(0, 900), rect(0, 0, 100, 160))
        assertEquals(IntOffset(0, -20), clamp)
    }

    // ── Oversized popups keep their top-left ──────────────────────────────

    @Test
    fun `a popup taller than the work area is aligned to the top`() {
        val clamp = clampAt(windowAt(100, 200), rect(0, 0, 200, 1400))
        // Top-left wins: the menu's first items stay reachable.
        assertEquals(IntOffset(0, -200), clamp)
        assertEquals(0, screenRect(windowAt(100, 200), rect(0, 0, 200, 1400), clamp).top)
    }

    @Test
    fun `a popup wider than the work area is aligned to the left`() {
        val clamp = clampAt(windowAt(300, 100), rect(0, 0, 2400, 200))
        assertEquals(IntOffset(-300, 0), clamp)
        assertEquals(0, screenRect(windowAt(300, 100), rect(0, 0, 2400, 200), clamp).left)
    }

    // ── The window's own coordinate space is never used as a screen ───────

    @Test
    fun `the clamp is independent of the owner window size`() {
        // A 1×1 tray anchor and a full-screen window at the same origin must
        // clamp identically — the whole point of #569 is that the *window* is
        // not the reference rect.
        val fromTinyWindow = clampAt(windowAt(1850, 1010), rect(0, 0, 240, 200))
        val fromBigWindow = clampAt(windowAt(1850, 1010), rect(0, 0, 240, 200))
        assertEquals(fromTinyWindow, fromBigWindow)
        assertEquals(IntOffset(1920 - 240 - 1850, 1040 - 200 - 1010), fromTinyWindow)
    }

    // ── Multi-display ─────────────────────────────────────────────────────

    @Test
    fun `a popup on the secondary display clamps to that display's work area`() {
        val geometry =
            PopupScreenGeometry(
                parentContentOriginPx = IntOffset(2400, 100),
                workAreasPx = listOf(PRIMARY_WORK, SECONDARY_WORK),
            )
        // 2400 + 1000 = 3400 → past the secondary's right edge (3200).
        val clamp = popupScreenClampOffset(rect(1000, 0, 300, 200), geometry)
        assertEquals(IntOffset(3200 - 300 - 3400, 0), clamp)
    }

    @Test
    fun `a popup on the secondary display is not yanked onto the primary`() {
        val geometry =
            PopupScreenGeometry(
                parentContentOriginPx = IntOffset(2400, 200),
                workAreasPx = listOf(PRIMARY_WORK, SECONDARY_WORK),
            )
        // Well inside the secondary display: clamping against the primary
        // work area (the bug a primary-monitor-only lookup would have) would
        // have dragged it back to x < 1920.
        assertEquals(IntOffset.Zero, popupScreenClampOffset(rect(100, 100, 300, 200), geometry))
    }

    @Test
    fun `a popup that overlaps two displays clamps to the one it covers most`() {
        val geometry =
            PopupScreenGeometry(
                parentContentOriginPx = IntOffset(1800, 300),
                workAreasPx = listOf(PRIMARY_WORK, SECONDARY_WORK),
            )
        // 1800 + 40 = 1840 → 80 px on the primary, 220 px on the secondary.
        // The secondary wins, and the frame is already inside it after the
        // left clamp to 1920.
        val frame = rect(40, 0, 300, 200)
        val clamp = popupScreenClampOffset(frame, geometry)
        assertEquals(IntOffset(1920 - 1840, 0), clamp)
    }

    @Test
    fun `a fully offscreen popup returns to the owner's display`() {
        val geometry =
            PopupScreenGeometry(
                parentContentOriginPx = IntOffset(2400, 300),
                workAreasPx = listOf(PRIMARY_WORK, SECONDARY_WORK),
            )
        // Below every work area — overlaps nothing, so the display hosting the
        // owner (the secondary) decides.
        val clamp = popupScreenClampOffset(rect(0, 900, 200, 200), geometry)
        val landed = screenRect(geometry.parentContentOriginPx, rect(0, 900, 200, 200), clamp)
        assertTrue(landed in SECONDARY_WORK, "landed on the wrong display: $landed")
    }

    // ── Idempotence: the layers re-clamp on every owner move ──────────────

    @Test
    fun `clamping an already-clamped frame is a no-op`() {
        val origin = windowAt(1800, 1000)
        val frame = rect(60, 60, 400, 400)
        val first = clampAt(origin, frame)
        val moved = IntRect(frame.left + first.x, frame.top + first.y, frame.right + first.x, frame.bottom + first.y)
        assertEquals(IntOffset.Zero, clampAt(origin, moved))
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private companion object {
        val PRIMARY_WORK = IntRect(0, 0, 1920, 1040)
        val SECONDARY_WORK = IntRect(1920, 0, 3200, 1024)

        fun rect(
            x: Int,
            y: Int,
            w: Int,
            h: Int,
        ) = IntRect(x, y, x + w, y + h)

        fun windowAt(
            x: Int,
            y: Int,
        ) = IntOffset(x, y)

        /** Clamp against the single-display fixture. */
        fun clampAt(
            parentOrigin: IntOffset,
            frameInParent: IntRect,
        ): IntOffset =
            popupScreenClampOffset(
                frameInParent,
                PopupScreenGeometry(parentOrigin, listOf(PRIMARY_WORK)),
            )

        /** Where [frameInParent] lands on screen once [clamp] is applied. */
        fun screenRect(
            parentOrigin: IntOffset,
            frameInParent: IntRect,
            clamp: IntOffset,
        ): IntRect =
            IntRect(
                left = parentOrigin.x + frameInParent.left + clamp.x,
                top = parentOrigin.y + frameInParent.top + clamp.y,
                right = parentOrigin.x + frameInParent.right + clamp.x,
                bottom = parentOrigin.y + frameInParent.bottom + clamp.y,
            )

        operator fun IntRect.contains(inner: IntRect): Boolean =
            inner.left >= left && inner.top >= top && inner.right <= right && inner.bottom <= bottom
    }
}
