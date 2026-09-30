package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.nucleusframework.window.tao.popup.PopupFrameRecord
import dev.nucleusframework.window.tao.popup.TaoPopupDiagnostics
import kotlinx.coroutines.delay
import java.awt.event.InputEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Headful battery for the **draw margin's input contract** on native popup
 * layers.
 *
 * A native popup layer's surface extends 32 dp past `boundsInWindow` so shadows
 * and the dialog appearance animation are not clipped (`popupDrawBounds`). That
 * margin is transparent, and every backend has to make it transparent to input
 * as well, or an open popup grows an invisible dead ring: a click on a button
 * 20 px beside a menu would dismiss the menu without ever pressing the button,
 * and hovering past the menu's edge would freeze the owner window's hover state.
 *
 * Windows and macOS get this from the OS — the layer hands the *content* rect to
 * `nativeSetFrameInWindow` / `nativeSetInteractiveRegions`, so the display
 * server routes a margin click to the parent. GTK's input shaping does not take
 * on a popup toplevel (the region reaches GDK and the X window keeps its full
 * input shape), so the Linux layer routes the event to the owner itself —
 * `TaoPopupHostLinux.forwardMarginPointer`.
 *
 * The cases drive a real pointer with [Robot] against a real popup and assert on
 * what the **owner window's scene** received, which is the only thing that tells
 * a pass-through apart from a swallow.
 */
internal object NativePopupMarginInputHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            ownerWindowReceivesAPlainPress(),
            marginPressReachesTheOwnerWindow(),
            marginMoveReachesTheOwnerWindow(),
            contentPressDoesNotReachTheOwnerWindow(),
            compositorPlacedPopupReanchorsWhenItGrows(),
        )

    /**
     * Native Wayland only. A compositor-placed popup is an `xdg_popup`, and GDK
     * builds its positioner once, at map. A popup that re-measures afterwards
     * — a menu whose items size late — cannot apply the new size in place:
     * resizing the EGL buffer alone leaves the `xdg_surface` geometry at the
     * anchored size, the buffer/geometry disagreement of #502. The layer has to
     * re-map, which shows up as a second anchor.
     */
    private fun compositorPlacedPopupReanchorsWhenItGrows(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#569 a compositor-placed popup that grows after it is mapped re-anchors",
            skip = {
                when {
                    !isNativeWayland -> "compositor placement is a native-Wayland path"
                    else -> null
                }
            },
            nativePopupLayers = true,
            paintDefaultBackground = false,
            content = { Content() },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            settle(POINTER_SETTLE_MILLIS)
            TaoPopupDiagnostics.reset()
            popupHeightDp.value = POPUP_H_DP
            popupShown.value = true
            try {
                awaitUntil("the popup took the compositor-placed path") {
                    TaoPopupDiagnostics.lastCompositorPlaced == true
                }
                awaitUntil("the popup anchored once") { TaoPopupDiagnostics.compositorAnchorCount >= 1 }
                settle(REANCHOR_SETTLE_MILLIS)
                val before = TaoPopupDiagnostics.compositorAnchorCount
                popupHeightDp.value = POPUP_H_DP + POPUP_GROWTH_DP
                awaitUntil(
                    "the popup re-anchored at its new size",
                    detail = { "anchors before=$before now=${TaoPopupDiagnostics.compositorAnchorCount}" },
                ) { TaoPopupDiagnostics.compositorAnchorCount > before }
            } finally {
                popupShown.value = false
                popupHeightDp.value = POPUP_H_DP
            }
        }

    /**
     * The guard the other cases lean on: without it, "the owner saw nothing"
     * is as consistent with a broken driver as with a swallowed press.
     */
    private fun ownerWindowReceivesAPlainPress(): TaoWindowTestCase =
        marginCase("#569 the owner window receives a press with no popup open") {
            val rect = requireNotNull(bounds()) { "window not mapped" }
            val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
            val x = ((rect[0] + rect[2] / 2) / scale).roundToInt()
            val y = ((rect[1] + rect[3] / 2) / scale).roundToInt()
            ownerPresses.value = 0
            clickAt(x, y)
            awaitUntil("the owner window received the press at ($x,$y)") { ownerPresses.value > 0 }
        }

    /**
     * The reported failure: a press in the margin is the popup window's by
     * accident of geometry, and the owner never sees it.
     */
    private fun marginPressReachesTheOwnerWindow(): TaoWindowTestCase =
        marginCase("#569 a press in a popup's draw margin reaches the owner window") {
            val record = openPopupAndSettle()
            val (x, y) = marginPointOf(record)
            ownerPresses.value = 0
            clickAt(x, y)
            awaitUntil(
                "the owner window received the margin press",
                detail = { "point=($x,$y) ${describe(record)}" },
            ) { ownerPresses.value > 0 }
        }

    /**
     * The same ring, in its quieter form: pointer moves over the margin belong
     * to the owner too, or its hover state freezes within 32 dp of any open
     * popup.
     */
    private fun marginMoveReachesTheOwnerWindow(): TaoWindowTestCase =
        marginCase("#569 a pointer move over a popup's draw margin reaches the owner window") {
            val record = openPopupAndSettle()
            val (x, y) = marginPointOf(record)
            // Park the pointer well away first, so the move under test is a real
            // transition rather than a repeat of wherever the last case left it.
            val rect = requireNotNull(bounds()) { "window not mapped" }
            val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
            moveTo(
                (rect[0] / scale).roundToInt() + PARK_INSET_PX,
                (rect[1] / scale).roundToInt() + PARK_INSET_PX,
            )
            ownerMoves.value = 0
            moveTo(x, y)
            awaitUntil(
                "the owner window received the margin move",
                detail = { "point=($x,$y) ${describe(record)}" },
            ) { ownerMoves.value > 0 }
        }

    /**
     * The other half of the contract: the *content* still belongs to the popup.
     * A fix that opened the whole surface to the parent would pass the two cases
     * above and break every menu.
     */
    private fun contentPressDoesNotReachTheOwnerWindow(): TaoWindowTestCase =
        marginCase("#569 a press on a popup's content does not reach the owner window") {
            val record = openPopupAndSettle()
            val content = record.contentOnScreenPx
            val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
            val x = ((content.left + content.right) / 2 / scale).roundToInt()
            val y = ((content.top + content.bottom) / 2 / scale).roundToInt()
            ownerPresses.value = 0
            popupPresses.value = 0
            clickAt(x, y)
            awaitUntil("the popup received the press on its content") { popupPresses.value > 0 }
            settle(POINTER_SETTLE_MILLIS)
            check(ownerPresses.value == 0) {
                "a press on the popup's content must not also reach the owner window; ${describe(record)}"
            }
        }

    // ── Case scaffolding ──────────────────────────────────────────────────

    private val popupShown = mutableStateOf(false)
    private val ownerPresses = mutableStateOf(0)
    private val ownerMoves = mutableStateOf(0)
    private val popupPresses = mutableStateOf(0)
    private val popupHeightDp = mutableStateOf(POPUP_H_DP)

    /**
     * Owner content that counts what the window's own scene receives, and a
     * popup parked in the middle of it so its whole draw margin still lands on
     * the owner window — the margin has to be over the parent for a
     * pass-through to be observable at all.
     */
    @Composable
    private fun Content() {
        val shown by popupShown
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF203040))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            when (awaitPointerEvent().type) {
                                PointerEventType.Press -> ownerPresses.value++
                                PointerEventType.Move -> ownerMoves.value++
                                else -> Unit
                            }
                        }
                    }
                },
        )
        if (shown) {
            Popup(alignment = Alignment.Center) {
                val height by popupHeightDp
                Box(
                    Modifier
                        .size(POPUP_W_DP.dp, height.dp)
                        .background(Color.Magenta)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    if (awaitPointerEvent().type == PointerEventType.Press) {
                                        popupPresses.value++
                                    }
                                }
                            }
                        },
                )
            }
        }
    }

    private fun marginCase(
        name: String,
        driver: suspend TaoWindowTestScope.() -> Unit,
    ): TaoWindowTestCase =
        TaoWindowTestCase(
            name = name,
            skip = ::skipReason,
            nativePopupLayers = true,
            // The scope is a ColumnScope and the harness's default background
            // is a `fillMaxSize` sibling: leaving it on would take the whole
            // height and lay this case's content out at zero, where nothing
            // hit-tests and every assertion here would hold for the wrong
            // reason.
            paintDefaultBackground = false,
            content = { Content() },
            driver = {
                awaitUntil("window mapped") { window.hasRealFramePx() }
                window.setAlwaysOnTop(true)
                window.focus()
                centerWindow()
                settle(POINTER_SETTLE_MILLIS)
                try {
                    driver()
                } finally {
                    popupShown.value = false
                    window.setAlwaysOnTop(false)
                }
            },
        )

    private suspend fun TaoWindowTestScope.openPopupAndSettle(): PopupFrameRecord {
        TaoPopupDiagnostics.reset()
        popupShown.value = true
        awaitUntil("popup layer pushed a frame") { TaoPopupDiagnostics.lastFrame != null }
        var previous: IntRect? = null
        var stable = 0
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MILLIS
        while (stable < STABLE_FRAMES) {
            delay(POLL_MILLIS)
            val frame = TaoPopupDiagnostics.lastFrame?.frameOnScreenPx
            stable = if (frame != null && frame == previous) stable + 1 else 0
            previous = frame
            check(System.currentTimeMillis() < deadline) { "popup frame never settled (last=$frame)" }
        }
        val record = requireNotNull(TaoPopupDiagnostics.lastFrame)
        check(record.frameOnScreenPx.right > record.contentOnScreenPx.right) {
            "this case needs a real draw margin to aim at; ${describe(record)}"
        }
        return record
    }

    /**
     * A screen point (logical, as [Robot] speaks) inside the popup's surface but
     * outside its content: halfway into the right-hand margin, level with the
     * content's vertical centre.
     */
    private fun TaoWindowTestScope.marginPointOf(record: PopupFrameRecord): Pair<Int, Int> {
        val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
        val content = record.contentOnScreenPx
        val frame = record.frameOnScreenPx
        val xPx = (content.right + frame.right) / 2
        val yPx = (content.top + content.bottom) / 2
        return (xPx / scale).roundToInt() to (yPx / scale).roundToInt()
    }

    private suspend fun TaoWindowTestScope.clickAt(
        x: Int,
        y: Int,
    ) {
        moveTo(x, y)
        HeadfulRobot.notePress()
        HeadfulRobot.inject { robot ->
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(CLICK_HOLD_MILLIS)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
        settle(POINTER_SETTLE_MILLIS)
    }

    /**
     * Parks the pointer at a logical screen point and lets the scene catch up.
     *
     * Two hops, the second a couple of pixels: a warp into a *different* window
     * arrives there as an enter, not as motion, and a layer that only forwards
     * motion would see nothing. The short second hop happens inside the window
     * the first one landed in, so a real move is always delivered.
     */
    private suspend fun TaoWindowTestScope.moveTo(
        x: Int,
        y: Int,
    ) {
        HeadfulRobot.inject { robot ->
            robot.mouseMove(x - NUDGE_PX, y - NUDGE_PX)
            Thread.sleep(NUDGE_PAUSE_MILLIS)
            robot.mouseMove(x, y)
        }
        HeadfulRobot.noteAim(x, y)
        settle(POINTER_SETTLE_MILLIS)
    }

    private suspend fun TaoWindowTestScope.centerWindow() {
        val work =
            dev.nucleusframework.window.tao.TaoMonitors
                .forWindow(window)
                .workAreaPx
        val rect = requireNotNull(bounds()) { "window not mapped" }
        val x = work.left + (work.width - rect[2].toInt()) / 2
        val y = work.top + (work.height - rect[3].toInt()) / 2
        window.setOuterPositionPx(x, y)
        awaitUntil("window settled at ${x}x$y", detail = { "bounds=${bounds()?.toList()}" }) {
            val b = bounds() ?: return@awaitUntil false
            abs(b[0] - x) <= MOVE_TOLERANCE_PX && abs(b[1] - y) <= MOVE_TOLERANCE_PX
        }
        settle(POINTER_SETTLE_MILLIS)
    }

    private fun TaoWindowTestScope.describe(record: PopupFrameRecord): String =
        "frame=${record.frameOnScreenPx} content=${record.contentOnScreenPx} " +
            "ownerPresses=${ownerPresses.value} ownerMoves=${ownerMoves.value} " +
            "popupPresses=${popupPresses.value} " +
            "window=${bounds()?.toList()} scale=${window.scaleFactor}"

    private val isNativeWayland: Boolean
        get() {
            val forcedX11 =
                System.getenv("GDK_BACKEND")?.split(',')?.firstOrNull() == "x11" ||
                    System.getenv("NUCLEUS_TAO_LINUX_RENDERER").orEmpty().equals("x11", ignoreCase = true)
            return System.getenv("WAYLAND_DISPLAY") != null && !forcedX11
        }

    private fun skipReason(): String? =
        when {
            java.awt.GraphicsEnvironment.isHeadless() -> "no display for Robot input"
            HeadfulRobot.unavailableReason != null -> HeadfulRobot.unavailableReason
            else -> null
        }

    private const val POPUP_W_DP = 220
    private const val POPUP_H_DP = 160
    private const val POPUP_GROWTH_DP = 90
    private const val PARK_INSET_PX = 12
    private const val NUDGE_PX = 3
    private const val NUDGE_PAUSE_MILLIS = 40L
    private const val POINTER_SETTLE_MILLIS = 400L
    private const val REANCHOR_SETTLE_MILLIS = 700L
    private const val CLICK_HOLD_MILLIS = 60L
    private const val POLL_MILLIS = 50L
    private const val SETTLE_TIMEOUT_MILLIS = 10_000L
    private const val STABLE_FRAMES = 4
    private const val MOVE_TOLERANCE_PX = 8L
}
