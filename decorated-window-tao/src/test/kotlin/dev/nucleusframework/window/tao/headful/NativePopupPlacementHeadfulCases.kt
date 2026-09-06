package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge
import dev.nucleusframework.window.tao.ffi.PopupNativeBridgeWindows
import dev.nucleusframework.window.tao.popup.PopupFrameRecord
import dev.nucleusframework.window.tao.popup.TaoPopupDiagnostics
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Headful battery for issue #569 — "nativePopupLayers: popups position against
 * the window, not the screen".
 *
 * With `nativePopupLayers = true` a Compose `Popup` becomes a real OS window
 * that escapes the owner. Native placement was already correct; the *decision*
 * was not, in two compounding ways:
 *
 *  1. The layers built a work-area-sized `WindowInfo` so popups could lay out
 *     and flip against the display — but then `setContent` replayed the parent
 *     window's composition locals *over* it, so `Popup.skiko.kt` read the owner
 *     window's `containerSize` and clipped every popup back into the window.
 *  2. Even with the work area in force, that box is rooted at the window's
 *     content top-left, not at the work-area origin. A `DropdownMenu` in a
 *     window near the bottom of the display did not flip up — Compose believed
 *     a whole work area of room was left below the anchor — and walked off the
 *     screen.
 *
 * A `Dialog` goes through the same layer machinery but must *not* follow the
 * display: `Dialog.skiko.kt` centres it in `containerSize`, so it keeps the
 * owner window as its box (cases 12-13).
 *
 * Each case places a real window at a real position, opens a popup, and asserts
 * against [TaoPopupDiagnostics] — the frame the layer actually pushed, in global
 * screen pixels. `boundsInWindow` cannot answer "did the popup land on screen":
 * it is deliberately left unclamped, because Compose's own hit-testing is
 * expressed in it.
 *
 * The two halves matter equally. A clamp is only correct if it also *doesn't*
 * fire — cases 1, 5, 9 and 12 fail if the fix over-reaches and starts treating
 * the owner window (or the display) as everyone's reference rect.
 *
 * Skipped on native Wayland: a popup there is a `wl_subsurface` positioned
 * relative to the parent surface, with no global position to clamp against, so
 * the Linux host reports no screen geometry at all.
 */
internal object NativePopupPlacementHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            popupInsideIsNotMoved(),
            popupAtBottomEdgeIsClamped(),
            popupAtRightEdgeIsClamped(),
            popupAtBottomRightCornerIsClamped(),
            popupEscapesTheOwnerWindowWhenTheScreenHasRoom(),
            popupAboveScreenTopIsClamped(),
            oversizedPopupKeepsItsTopLeft(),
            dropdownMenuAtBottomEdgeStaysOnScreen(),
            popupLargerThanItsOwnerWindowStaysOnScreen(),
            ownerMoveReclampsAnOpenPopup(),
            nativeWindowRectMatchesTheClampedFrame(),
            dialogStaysCentredInItsWindow(),
            dialogNearTheScreenEdgeIsStillClamped(),
            dialogSurfaceCoversItsShadow(),
        )

    // ── 1. no gratuitous shifting ─────────────────────────────────────────

    private fun popupInsideIsNotMoved(): TaoWindowTestCase =
        popupCase("#569 a popup with room around it is placed exactly where Compose asked") {
            centerWindow()
            val record = openPopup(offset = IntOffset(POPUP_INSET_PX, POPUP_INSET_PX))
            checkOnWorkArea(record)
            check(record.clampOffsetPx == IntOffset.Zero) {
                "a popup with room on every side must not be moved, got ${record.clampOffsetPx}"
            }
        }

    // ── 2-4. the reported failure ─────────────────────────────────────────
    //
    // The offsets matter. `Popup(alignment)` aligns inside the *parent* scene
    // (the owner window's content), so an alignment alone never leaves the
    // window and never reproduces #569. The extra offset is what pushes the
    // popup past the window edge — where `Popup.skiko.kt` happily allows it,
    // because its clip box is the work-area-sized virtual screen rooted at the
    // window, and only there does the missing screen origin show up.

    private fun popupAtBottomEdgeIsClamped(): TaoWindowTestCase =
        popupCase("#569 a popup anchored past the bottom of the work area slides back in") {
            moveWindow(fromBottomPx = edgeMarginPx())
            val record = openPopup(alignment = Alignment.BottomStart, offset = IntOffset(0, POPUP_H_DP))
            checkOnWorkArea(record)
            check(record.clampOffsetPx.y < 0) {
                "expected an upward clamp at the bottom edge; ${describe(record)}"
            }
        }

    private fun popupAtRightEdgeIsClamped(): TaoWindowTestCase =
        popupCase("#569 a popup anchored past the right of the work area slides back in") {
            moveWindow(fromRightPx = edgeMarginPx())
            val record = openPopup(alignment = Alignment.TopEnd, offset = IntOffset(POPUP_W_DP, 0))
            checkOnWorkArea(record)
            check(record.clampOffsetPx.x < 0) {
                "expected a leftward clamp at the right edge; ${describe(record)}"
            }
        }

    private fun popupAtBottomRightCornerIsClamped(): TaoWindowTestCase =
        popupCase("#569 a popup in the bottom-right corner clamps on both axes") {
            moveWindow(fromBottomPx = edgeMarginPx(), fromRightPx = edgeMarginPx())
            val record =
                openPopup(
                    alignment = Alignment.BottomEnd,
                    offset = IntOffset(POPUP_W_DP, POPUP_H_DP),
                )
            checkOnWorkArea(record)
            check(record.clampOffsetPx.x < 0 && record.clampOffsetPx.y < 0) {
                "expected both axes to clamp in the corner; ${describe(record)}"
            }
        }

    // ── 5-6. the window edge is not a screen edge ─────────────────────────

    private fun popupEscapesTheOwnerWindowWhenTheScreenHasRoom(): TaoWindowTestCase =
        popupCase("#569 a popup outside the owner window is left alone while the screen has room") {
            centerWindow()
            val windowRight = windowRightPx()
            // Offset past the window's own right edge. The whole point of
            // native popup layers is that a popup may leave the window; a
            // clamp that used the window as its reference rect (the pre-#569
            // behaviour, only from the other side) would drag it back in.
            val record = openPopup(offset = IntOffset(windowWidthDp() + POPUP_ESCAPE_DP, 0))
            checkOnWorkArea(record)
            check(record.contentOnScreenPx.left > windowRight) {
                "popup must be allowed outside the owner window: " +
                    "content=${record.contentOnScreenPx} windowRight=$windowRight"
            }
            check(record.clampOffsetPx == IntOffset.Zero) {
                "nothing to clamp here — the popup is off the window, not off the screen; " +
                    describe(record)
            }
        }

    private fun popupAboveScreenTopIsClamped(): TaoWindowTestCase =
        popupCase("#569 a popup above the top of the work area slides down") {
            // Compose clips popup positions at 0 in *window* coordinates, so a
            // popup can only end up above the work area when the window itself
            // does. Drag the window's top off the top of the screen — the
            // everyday way a user gets there.
            moveWindow(abovePx = ABOVE_SCREEN_PX)
            val record = openPopup()
            checkOnWorkArea(record)
            check(record.clampOffsetPx.y > 0) {
                "expected a downward clamp above the work area; ${describe(record)}"
            }
        }

    // ── 7. oversized ──────────────────────────────────────────────────────

    private fun oversizedPopupKeepsItsTopLeft(): TaoWindowTestCase =
        popupCase("#569 a popup taller than the work area is aligned to the work-area top") {
            centerWindow()
            val work = workArea()
            val tallDp = (work.height / scale()).toInt() + OVERSIZE_SLACK_DP
            val record = openPopup(heightDp = tallDp)
            val frame = record.contentOnScreenPx
            // It cannot fit; the contract is that the *top* stays visible (a
            // menu's first items, a tooltip's first line).
            check(frame.top == work.top) {
                "an oversized popup must align to the work-area top: frame=$frame work=$work"
            }
            check(frame.left >= work.left) {
                "left edge escaped the work area: frame=$frame work=$work"
            }
        }

    // ── 8. the component the issue names ──────────────────────────────────

    private fun dropdownMenuAtBottomEdgeStaysOnScreen(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#569 a DropdownMenu near the bottom of the display stays on screen",
            skip = ::skipReason,
            nativePopupLayers = true,
            content = { DropdownSlot() },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            moveWindow(fromBottomPx = edgeMarginPx())
            TaoPopupDiagnostics.reset()
            dropdownExpanded.value = true
            try {
                checkOnWorkArea(awaitSettledRecord())
            } finally {
                dropdownExpanded.value = false
            }
        }

    // ── 9. the tray-anchor pattern ────────────────────────────────────────

    private fun popupLargerThanItsOwnerWindowStaysOnScreen(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#569 a popup far larger than its owner window is placed against the display",
            skip = ::skipReason,
            nativePopupLayers = true,
            size = DpSize(TINY_WINDOW_DP.dp, TINY_WINDOW_DP.dp),
            content = { PopupSlot() },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            moveWindow(fromBottomPx = edgeMarginPx(), fromRightPx = edgeMarginPx())
            val record =
                openPopup(
                    widthDp = POPUP_W_DP * 2,
                    heightDp = POPUP_H_DP * 2,
                )
            checkOnWorkArea(record)
            // The owner is TINY_WINDOW_DP square and the popup many times that
            // — the shape #569 broke worst, since the clamp reference used to
            // be a work-area-sized box rooted at this tiny window.
            val minWidthPx = (POPUP_W_DP * scale()).toInt()
            check(record.contentOnScreenPx.width >= minWidthPx) {
                "popup collapsed toward the owner window size: ${record.contentOnScreenPx}"
            }
        }

    // ── 10. re-clamp on owner move ────────────────────────────────────────

    private fun ownerMoveReclampsAnOpenPopup(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#569 moving the owner window re-clamps an already-open popup",
            skip = {
                // macOS panels are AppKit child windows that ride along with
                // the owner; there is no owner-move re-clamp there by design
                // (documented on TaoPopupSceneLayer).
                skipReason() ?: "no owner-move re-clamp on macOS".takeIf { Platform.Current == Platform.MacOS }
            },
            nativePopupLayers = true,
            content = { PopupSlot() },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            centerWindow()
            val opened =
                openPopup(
                    alignment = Alignment.BottomStart,
                    offset = IntOffset(0, POPUP_H_DP),
                    closeAfter = false,
                )
            try {
                check(opened.clampOffsetPx == IntOffset.Zero) {
                    "popup should open unclamped in the middle of the screen, got ${opened.clampOffsetPx}"
                }
                // Move the window into the bottom-right corner with the popup
                // still open: the owner-move listener must re-issue the frame.
                TaoPopupDiagnostics.reset()
                moveWindow(fromBottomPx = edgeMarginPx(), fromRightPx = edgeMarginPx())
                awaitUntil(
                    "popup re-clamped after the owner moved",
                    detail = { "last=${TaoPopupDiagnostics.lastFrame?.frameOnScreenPx}" },
                ) {
                    TaoPopupDiagnostics.lastFrame?.clampOffsetPx?.let { it != IntOffset.Zero } == true
                }
                checkOnWorkArea(requireNotNull(TaoPopupDiagnostics.lastFrame))
            } finally {
                popupRequest.value = null
            }
        }

    // ── 11. the OS agrees ─────────────────────────────────────────────────

    private fun nativeWindowRectMatchesTheClampedFrame(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#569 the popup window's real screen rect is the clamped one",
            // Reads the popup's own HWND back through Win32. The equivalent
            // introspection has no counterpart for a bare NSPanel handle or a
            // Tao popup window here, so the round-trip is Windows-only; the
            // other platforms are covered by the frame assertions above.
            skip = { skipReason() ?: "Windows only".takeIf { Platform.Current != Platform.Windows } },
            nativePopupLayers = true,
            content = { PopupSlot() },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            moveWindow(fromBottomPx = edgeMarginPx())
            val record =
                openPopup(
                    alignment = Alignment.BottomStart,
                    offset = IntOffset(0, POPUP_H_DP),
                    closeAfter = false,
                )
            try {
                checkOnWorkArea(record)
                val popupHwnd = PopupNativeBridgeWindows.nativeContentHwnd(record.panelHandle)
                check(popupHwnd != 0L) { "popup HWND not resolvable from panel=${record.panelHandle}" }
                val rect =
                    requireNotNull(NativeTaoWindowsDecoBridge.nativeGetWindowRect(popupHwnd)) {
                        "GetWindowRect failed for the popup HWND"
                    }
                val actual =
                    IntRect(
                        left = rect[0].toInt(),
                        top = rect[1].toInt(),
                        right = (rect[0] + rect[2]).toInt(),
                        bottom = (rect[1] + rect[3]).toInt(),
                    )
                // To the pixel: this is what proves the Kotlin-side clamp and
                // the native ClientToScreen path neither double-apply nor
                // cancel the offset.
                check(actual == record.frameOnScreenPx) {
                    "OS rect $actual disagrees with the reported frame ${record.frameOnScreenPx}"
                }
                // The surface carries the draw margin past the content, so the
                // OS rect may hang off the work area — the content must not.
                val work = workArea()
                val content =
                    record.contentOnScreenPx.translate(
                        IntOffset(actual.left - record.frameOnScreenPx.left, actual.top - record.frameOnScreenPx.top),
                    )
                check(content.top >= work.top && content.bottom <= work.bottom) {
                    "the OS placed the popup outside the work area: $content vs $work"
                }
            } finally {
                popupRequest.value = null
            }
        }

    // ── 12-13. dialogs belong to the window, not the display ──────────────

    private fun dialogStaysCentredInItsWindow(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#569 a Dialog stays centred in its window, not on the display",
            skip = ::skipReason,
            nativePopupLayers = true,
            content = { DialogSlot() },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            // Deliberately *not* centred and not maximized: a layer that used
            // the work area as every layer's container would centre the dialog
            // on the display, which only coincides with the window centre for
            // a maximized window on the primary display.
            moveWindow(fromRightPx = edgeMarginPx() * DIALOG_WINDOW_INSET_FACTOR)
            TaoPopupDiagnostics.reset()
            dialogShown.value = true
            try {
                val record = awaitSettledRecord()
                // The content, not the surface: the dialog's appearance animation
                // inflates the surface below the layout bounds.
                val frame = record.contentOnScreenPx
                val rect = requireNotNull(bounds()) { "window not mapped" }
                val windowCentreX = (rect[0] + rect[2] / 2).toInt()
                val windowCentreY = (rect[1] + rect[3] / 2).toInt()
                val dx = abs(frame.left + frame.width / 2 - windowCentreX)
                val dy = abs(frame.top + frame.height / 2 - windowCentreY)
                // Tolerance covers the decoration inset between the window's
                // outer rect (what `bounds()` reports) and its content rect
                // (what the dialog centres in).
                check(dx <= DIALOG_CENTRE_TOLERANCE_PX && dy <= DIALOG_CENTRE_TOLERANCE_PX) {
                    "dialog is not centred in its window: frame=$frame " +
                        "windowCentre=($windowCentreX, $windowCentreY) off by ($dx, $dy)"
                }
                check(record.clampOffsetPx == IntOffset.Zero) {
                    "a dialog inside its window needs no clamp; ${describe(record)}"
                }
            } finally {
                dialogShown.value = false
            }
        }

    private fun dialogSurfaceCoversItsShadow(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#569 a Dialog's surface is inflated around the shadow it draws",
            skip = ::skipReason,
            nativePopupLayers = true,
            content = { DialogSlot() },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            centerWindow()
            TaoPopupDiagnostics.reset()
            dialogShadow.value = true
            dialogShown.value = true
            try {
                val record = awaitSettledRecord()
                val frame = record.frameOnScreenPx
                val content = record.contentOnScreenPx
                // The layout bounds are the content; an in-scene layer draws its
                // elevation shadow past them into the window canvas, and a
                // separate OS surface must grow to hold it or clip it away.
                val coversEverySide =
                    frame.left < content.left &&
                        frame.top < content.top &&
                        frame.right > content.right &&
                        frame.bottom > content.bottom
                check(coversEverySide) {
                    "the surface must extend past the content on every side to hold the shadow: " +
                        "frame=$frame content=$content"
                }
                check(record.boundsInWindowPx.size == content.size) {
                    "the content frame must keep Compose's layout size; ${describe(record)}"
                }
            } finally {
                dialogShown.value = false
                dialogShadow.value = false
            }
        }

    private fun dialogNearTheScreenEdgeIsStillClamped(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#569 a Dialog whose window hangs off the display is clamped back on",
            skip = ::skipReason,
            nativePopupLayers = true,
            content = { DialogSlot() },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            // Window dragged off the top of the screen: centring in the window
            // is the right rule, but a dialog nobody can see is not — the same
            // clamp that saves popups applies.
            moveWindow(abovePx = ABOVE_SCREEN_PX * DIALOG_ABOVE_FACTOR)
            TaoPopupDiagnostics.reset()
            dialogShown.value = true
            try {
                val record = awaitSettledRecord()
                checkOnWorkArea(record)
                check(record.clampOffsetPx.y > 0) {
                    "expected the dialog to be pushed back onto the display; ${describe(record)}"
                }
            } finally {
                dialogShown.value = false
            }
        }

    // ── Case scaffolding ──────────────────────────────────────────────────

    /**
     * Popup geometry the *driver* chooses, after the window has been placed.
     *
     * Deliberately not a `LaunchedEffect(delay)`: #569 is about the position
     * decided at open time, so a case that opens the popup on a timer while the
     * window is still moving would be racing its own setup. One shared slot
     * across cases is safe — the harness runs them sequentially in a fresh
     * window each time.
     */
    private class PopupRequest(
        val widthDp: Int,
        val heightDp: Int,
        val offset: IntOffset,
        val alignment: Alignment,
    )

    private val popupRequest = mutableStateOf<PopupRequest?>(null)
    private val dropdownExpanded = mutableStateOf(false)
    private val dialogShown = mutableStateOf(false)
    private val dialogShadow = mutableStateOf(false)

    @Composable
    private fun PopupSlot() {
        val request by popupRequest
        val current = request ?: return
        Popup(alignment = current.alignment, offset = current.offset) {
            Box(Modifier.size(current.widthDp.dp, current.heightDp.dp).background(Color.Magenta))
        }
    }

    /**
     * A real `DropdownMenu` anchored at the **bottom** of the window content —
     * the everyday shape of #569. Compose opens a dropdown below its anchor and
     * only flips when the anchor is near the bottom of what it thinks the
     * screen is; anchored here, in a window sitting at the bottom of the
     * display, its window-rooted view of the screen sends the menu off it.
     */
    @Composable
    private fun DropdownSlot() {
        val expanded by dropdownExpanded
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            Box(Modifier.size(DROPDOWN_ANCHOR_DP.dp)) {
                DropdownMenu(expanded = expanded, onDismissRequest = { }) {
                    repeat(DROPDOWN_ITEMS) { index ->
                        DropdownMenuItem(onClick = { }) { Text("item $index") }
                    }
                }
            }
        }
    }

    /**
     * A `Dialog` — the other thing that lands in a scene layer, and the one
     * that must *not* be placed against the display. `Dialog.skiko.kt` puts it
     * at `containerSize.center`, so a layer reporting the work area as its
     * container would centre a window-owned dialog on the screen instead of on
     * its window.
     */
    @Composable
    private fun DialogSlot() {
        val shown by dialogShown
        val shadow by dialogShadow
        if (shown) {
            Dialog(onDismissRequest = { }) {
                Box(
                    Modifier
                        .size(DIALOG_W_DP.dp, DIALOG_H_DP.dp)
                        .then(if (shadow) Modifier.shadow(DIALOG_SHADOW_DP.dp) else Modifier)
                        .background(Color.Cyan),
                )
            }
        }
    }

    private fun popupCase(
        name: String,
        driver: suspend TaoWindowTestScope.() -> Unit,
    ): TaoWindowTestCase =
        TaoWindowTestCase(
            name = name,
            skip = ::skipReason,
            nativePopupLayers = true,
            content = { PopupSlot() },
            driver = {
                awaitUntil("window mapped") { window.hasRealFramePx() }
                driver()
            },
        )

    /** Opens the shared [PopupSlot] popup and returns its settled frame. */
    private suspend fun TaoWindowTestScope.openPopup(
        widthDp: Int = POPUP_W_DP,
        heightDp: Int = POPUP_H_DP,
        offset: IntOffset = IntOffset.Zero,
        alignment: Alignment = Alignment.TopStart,
        closeAfter: Boolean = true,
    ): PopupFrameRecord {
        TaoPopupDiagnostics.reset()
        popupRequest.value = PopupRequest(widthDp, heightDp, offset, alignment)
        val record = awaitSettledRecord()
        if (closeAfter) popupRequest.value = null
        return record
    }

    /**
     * Waits until the popup layer's pushed frame stops changing.
     *
     * The layers push a frame from their bootstrap measure pass too (the inner
     * scene has to render once before Compose can write `boundsInWindow` at
     * all), so the first record can predate the measured size. Settling is what
     * makes the assertions about the final position meaningful.
     */
    private suspend fun TaoWindowTestScope.awaitSettledRecord(): PopupFrameRecord {
        awaitUntil("popup layer pushed a frame") { TaoPopupDiagnostics.lastFrame != null }
        var previous: IntRect? = null
        var stable = 0
        val deadline = System.currentTimeMillis() + RECORD_SETTLE_TIMEOUT_MILLIS
        while (stable < STABLE_FRAMES) {
            delay(RECORD_POLL_MILLIS)
            val frame = TaoPopupDiagnostics.lastFrame?.frameOnScreenPx
            stable = if (frame != null && frame == previous) stable + 1 else 0
            previous = frame
            check(System.currentTimeMillis() < deadline) { "popup frame never settled (last=$frame)" }
        }
        return requireNotNull(TaoPopupDiagnostics.lastFrame)
    }

    // ── Assertions ────────────────────────────────────────────────────────

    /**
     * The #569 contract: the popup is fully inside its display's work area.
     * Judged on the content — the surface may carry a shadow margin past the
     * edge, exactly as an in-scene layer's shadow would.
     */
    private fun TaoWindowTestScope.checkOnWorkArea(record: PopupFrameRecord) {
        val frame = record.contentOnScreenPx
        val areas = TaoMonitors.all(window).map { it.workAreaPx }
        check(areas.any { frame.fitsIn(it) }) {
            "popup landed outside every work area: content=$frame areas=$areas " +
                "clamp=${record.clampOffsetPx} composeBounds=${record.boundsInWindowPx}"
        }
    }

    /**
     * Guards the edge cases against passing for the wrong reason: if the clamp
     * agreed with Compose's own decision, the window was not actually placed
     * somewhere that reproduces #569 and the case proves nothing.
     */
    private fun TaoWindowTestScope.checkClampDiverged(record: PopupFrameRecord) {
        check(record.clampOffsetPx != IntOffset.Zero) {
            "the clamp never fired — the window is not at an edge, so this case " +
                "is not exercising #569 (frame=${record.frameOnScreenPx} " +
                "composeBounds=${record.boundsInWindowPx} window=${bounds()?.toList()} " +
                "work=${workArea()} scale=${scale()})"
        }
    }

    private fun TaoWindowTestScope.describe(record: PopupFrameRecord): String =
        "frame=${record.frameOnScreenPx} content=${record.contentOnScreenPx} clamp=${record.clampOffsetPx} " +
            "composeBounds=${record.boundsInWindowPx} window=${bounds()?.toList()} " +
            "work=${workArea()} scale=${scale()}"

    private fun IntRect.fitsIn(other: IntRect): Boolean =
        left >= other.left && top >= other.top && right <= other.right && bottom <= other.bottom

    // ── Geometry helpers ──────────────────────────────────────────────────

    private fun TaoWindowTestScope.workArea(): IntRect = TaoMonitors.forWindow(window).workAreaPx

    private fun TaoWindowTestScope.scale(): Float = window.scaleFactor.takeIf { it > 0f } ?: 1f

    /** Margin the edge cases leave between the window and the work-area edge. */
    private fun TaoWindowTestScope.edgeMarginPx(): Int = (EDGE_MARGIN_DP * scale()).toInt()

    private fun TaoWindowTestScope.windowRightPx(): Int {
        val rect = requireNotNull(bounds()) { "window not mapped" }
        return (rect[0] + rect[2]).toInt()
    }

    /** The owner window's width in dp — the unit `Popup(offset =)` takes. */
    private fun TaoWindowTestScope.windowWidthDp(): Int {
        val rect = requireNotNull(bounds()) { "window not mapped" }
        return (rect[2] / scale()).toInt()
    }

    private suspend fun TaoWindowTestScope.centerWindow() {
        val work = workArea()
        val rect = requireNotNull(bounds()) { "window not mapped" }
        moveTo(
            work.left + (work.width - rect[2].toInt()) / 2,
            work.top + (work.height - rect[3].toInt()) / 2,
        )
    }

    /**
     * Places the window against a work-area edge — the geometry that makes
     * Compose's window-rooted flip decision wrong. Unconstrained axes are
     * centred.
     */
    private suspend fun TaoWindowTestScope.moveWindow(
        fromBottomPx: Int? = null,
        fromTopPx: Int? = null,
        fromRightPx: Int? = null,
        abovePx: Int? = null,
    ) {
        val work = workArea()
        val rect = requireNotNull(bounds()) { "window not mapped" }
        val w = rect[2].toInt()
        val h = rect[3].toInt()
        val x =
            if (fromRightPx != null) work.right - w - fromRightPx else work.left + (work.width - w) / 2
        val y =
            when {
                fromBottomPx != null -> work.bottom - h - fromBottomPx
                fromTopPx != null -> work.top + fromTopPx
                abovePx != null -> work.top - abovePx
                else -> work.top + (work.height - h) / 2
            }
        moveTo(x, y)
    }

    private suspend fun TaoWindowTestScope.moveTo(
        xPx: Int,
        yPx: Int,
    ) {
        window.setOuterPositionPx(xPx, yPx)
        awaitUntil(
            "window settled at ${xPx}x$yPx",
            detail = { "bounds=${bounds()?.toList()}" },
        ) {
            val rect = bounds() ?: return@awaitUntil false
            abs(rect[0] - xPx) <= MOVE_TOLERANCE_PX && abs(rect[1] - yPx) <= MOVE_TOLERANCE_PX
        }
        // The owner-move listener runs on the Tao loop; give the layers a frame
        // to react before anything reads the popup's frame back.
        settle(SETTLE_MILLIS)
    }

    private fun skipReason(): String? =
        if (Platform.Current == Platform.Linux && isNativeWayland) {
            "Wayland popups are parent-relative subsurfaces — no global position to clamp"
        } else {
            null
        }

    private val isNativeWayland: Boolean
        get() {
            val forcedX11 =
                System.getenv("GDK_BACKEND")?.split(',')?.firstOrNull() == "x11" ||
                    System.getenv("NUCLEUS_TAO_LINUX_RENDERER").orEmpty().equals("x11", ignoreCase = true)
            return System.getenv("WAYLAND_DISPLAY") != null && !forcedX11
        }

    private const val POPUP_W_DP = 240
    private const val POPUP_H_DP = 200
    private const val POPUP_INSET_PX = 20
    private const val TINY_WINDOW_DP = 120
    private const val EDGE_MARGIN_DP = 40
    private const val OVERSIZE_SLACK_DP = 200
    private const val POPUP_ESCAPE_DP = 24
    private const val ABOVE_SCREEN_PX = 260
    private const val DIALOG_W_DP = 320
    private const val DIALOG_H_DP = 220
    private const val DIALOG_SHADOW_DP = 16
    private const val DIALOG_CENTRE_TOLERANCE_PX = 24
    private const val DIALOG_WINDOW_INSET_FACTOR = 2
    private const val DIALOG_ABOVE_FACTOR = 2
    private const val DROPDOWN_ANCHOR_DP = 60
    private const val DROPDOWN_ITEMS = 12
    private const val SETTLE_MILLIS = 600L
    private const val RECORD_POLL_MILLIS = 50L
    private const val RECORD_SETTLE_TIMEOUT_MILLIS = 10_000L
    private const val STABLE_FRAMES = 4
    private const val MOVE_TOLERANCE_PX = 8L
}
