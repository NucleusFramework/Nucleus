package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.window.DialogTitleBar
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.resolvedSizePolicy
import dev.nucleusframework.window.tao.showObservers
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * #532 — `Dp.Unspecified` on a window/dialog axis must wrap content, not
 * create a 0-tall (or NaN) native surface that Metal/EGL refuses to draw.
 *
 * #546 — and the position must be resolved against the *measured* size:
 * `Aligned(Center)` centres the window on the work area, a dialog centres on
 * its parent, once the wrap-content size is known.
 */
internal object UnspecifiedSizeHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            windowWrapContentHeight(),
            dialogWrapContentHeight(),
            windowWrapContentCentred(),
            dialogWrapContentCentredOnScreen(),
            dialogWrapContentCentredOnParent(),
            windowWrapContentWidthTitleBarSpans(),
            windowWrapContentMapsAtMeasuredSize(),
            dialogWrapContentMapsAtMeasuredSize(),
            dialogWrapContentFirstPassGetsRequestedWidth(),
            dialogWrapContentFillMaxSizeShowsAtOnce(),
        )

    /** What each window looked like at the instant [TaoWindow.show] ran, by handle — first show only. */
    private val firstShow = ConcurrentHashMap<Long, ShowSnapshot>()

    /** [bounds] is null when the native window did not even exist yet. */
    private class ShowSnapshot(
        val bounds: LongArray?,
        val wrapSettled: Boolean,
        val atNanos: Long = System.nanoTime(),
    )

    init {
        showObservers += { w ->
            firstShow.putIfAbsent(w.handle, ShowSnapshot(w.outerBoundsPx(), w.resolvedSizePolicy().settled.value))
        }
    }

    private fun windowWrapContentHeight(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#532 window wrap-content height maps with non-zero size",
            paintDefaultBackground = false,
            size = DpSize(WRAP_WIDTH_DP.dp, Dp.Unspecified),
            content = { RedBox() },
        ) {
            awaitUntil("window mapped with wrap-content height") {
                val b = bounds() ?: return@awaitUntil false
                if (b[2] <= 0 || b[3] <= 0) return@awaitUntil false
                b.wrapsContent(window)
            }
            val b = checkNotNull(bounds())
            val heightDp = b[3] / window.scaleFactor
            check(b.wrapsContent(window)) {
                "expected wrap-content height around ${CONTENT_HEIGHT_DP}dp, got ${heightDp}dp"
            }
        }

    private fun dialogWrapContentHeight(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#532 dialog wrap-content height maps with non-zero size",
            paintDefaultBackground = false,
            dialogSize = DpSize(WRAP_WIDTH_DP.dp, Dp.Unspecified),
            dialogContent = {
                DialogTitleBar { }
                RedBox()
            },
        ) {
            val dialog = checkNotNull(dialogWindow) { "dialog window never published" }
            awaitUntil("dialog mapped with wrap-content height") {
                val b = dialog.outerBoundsPx() ?: return@awaitUntil false
                if (b[2] <= 0 || b[3] <= 0) return@awaitUntil false
                b.wrapsContent(dialog)
            }
        }

    private fun windowWrapContentCentred(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#546 window wrap-content height centres on the work area",
            skip = { if (isNativeWayland) "xdg-shell ignores client positions" else null },
            paintDefaultBackground = false,
            windowState =
                WindowState(
                    size = DpSize(WRAP_WIDTH_DP.dp, Dp.Unspecified),
                    position = WindowPosition.Aligned(Alignment.Center),
                ),
            content = { RedBox() },
        ) {
            awaitUntil(
                "window centred at its wrap-content size",
                detail = { "bounds=${bounds()?.toList()} workArea=${workArea().toList()}" },
            ) {
                val b = bounds() ?: return@awaitUntil false
                b.wrapsContent(window) && centresMatch(b, workArea(), CENTRE_TOLERANCE_DP * window.scaleFactor)
            }
        }

    /** A parentless dialog centres on the screen, as AWT's `setLocationRelativeTo(null)`. */
    private fun dialogWrapContentCentredOnScreen(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#546 parentless dialog wrap-content height centres on the work area",
            skip = { if (isNativeWayland) "xdg-shell ignores client positions" else null },
            paintDefaultBackground = false,
            dialogSize = DpSize(WRAP_WIDTH_DP.dp, Dp.Unspecified),
            dialogContent = {
                DialogTitleBar { }
                RedBox()
            },
        ) {
            val dialog = checkNotNull(dialogWindow) { "dialog window never published" }
            awaitUntil(
                "dialog centred on the work area at its wrap-content size",
                detail = { "dialog=${dialog.outerBoundsPx()?.toList()} workArea=${workArea().toList()}" },
            ) {
                val d = dialog.outerBoundsPx() ?: return@awaitUntil false
                d.wrapsContent(dialog) && centresMatch(d, workArea(), CENTRE_TOLERANCE_DP * dialog.scaleFactor)
            }
        }

    private fun dialogWrapContentCentredOnParent(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#546 dialog wrap-content height centres on the parent",
            skip = { if (isNativeWayland) "xdg-shell ignores client positions" else null },
            paintDefaultBackground = false,
            dialogParentedToWindow = true,
            dialogSize = DpSize(WRAP_WIDTH_DP.dp, Dp.Unspecified),
            dialogContent = {
                DialogTitleBar { }
                RedBox()
            },
        ) {
            val dialog = checkNotNull(dialogWindow) { "dialog window never published" }
            awaitUntil(
                "dialog centred on its parent at its wrap-content size",
                detail = { "dialog=${dialog.outerBoundsPx()?.toList()} parent=${bounds()?.toList()}" },
            ) {
                val d = dialog.outerBoundsPx() ?: return@awaitUntil false
                val p = bounds() ?: return@awaitUntil false
                d.wrapsContent(dialog) && centresMatch(d, p, CENTRE_TOLERANCE_DP * dialog.scaleFactor)
            }
        }

    /**
     * #546 (follow-up): under an unspecified width the scene's wrap modifier
     * left the TitleBar an unbounded max width, so its `fillMaxWidth` collapsed
     * to the buttons. Once measured, the bar must span the window.
     */
    private fun windowWrapContentWidthTitleBarSpans(): TaoWindowTestCase {
        val titleBarWidthPx = AtomicInteger(0)
        val sceneWidthPx = AtomicInteger(0)
        return TaoWindowTestCase(
            name = "#546 wrap-content width: TitleBar spans the measured window",
            paintDefaultBackground = false,
            size = DpSize(Dp.Unspecified, WRAP_HEIGHT_DP.dp),
            content = {
                sceneWidthPx.set(LocalWindowInfo.current.containerSize.width)
                TitleBar(Modifier.onSizeChanged { titleBarWidthPx.set(it.width) }) { }
                RedBox()
            },
        ) {
            awaitUntil(
                "window mapped at its wrap-content width",
                detail = { "bounds=${bounds()?.toList()}" },
            ) {
                val b = bounds() ?: return@awaitUntil false
                val widthDp = b[2] / window.scaleFactor
                widthDp in WRAP_WIDTH_DP..(WRAP_WIDTH_DP + MAX_CHROME_DP)
            }
            awaitUntil(
                "TitleBar as wide as the scene",
                detail = { "titleBar=${titleBarWidthPx.get()}px scene=${sceneWidthPx.get()}px" },
            ) {
                val scene = sceneWidthPx.get()
                val settled = scene > 0 && scene <= (WRAP_WIDTH_DP + MAX_CHROME_DP) * window.scaleFactor
                settled && titleBarWidthPx.get() == scene
            }
        }
    }

    /**
     * #532 follow-up: a wrap-content window used to be shown at its creation
     * fallback (600dp tall) and only shrunk once the content was measured — a
     * blank frame of the wrong size on every open. It must map at the measured
     * size, and stay there.
     */
    private fun windowWrapContentMapsAtMeasuredSize(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#532 window wrap-content height is shown at its measured size",
            paintDefaultBackground = false,
            size = DpSize(WRAP_WIDTH_DP.dp, Dp.Unspecified),
            content = { RedBox() },
        ) {
            assertMappedAndStaysAtWrapSize(window)
        }

    private fun dialogWrapContentMapsAtMeasuredSize(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#532 dialog wrap-content height is shown at its measured size",
            paintDefaultBackground = false,
            dialogSize = DpSize(WRAP_WIDTH_DP.dp, Dp.Unspecified),
            dialogContent = {
                DialogTitleBar { }
                RedBox()
            },
        ) {
            assertMappedAndStaysAtWrapSize(checkNotNull(dialogWindow) { "dialog window never published" })
        }

    /**
     * #890 — under a wrap-content height the specified width must reach the
     * content in pixels from the very first pass. It was handed the dp value
     * as pixels (650 px at 200%), so text measured its height against half the
     * window's width.
     */
    private fun dialogWrapContentFirstPassGetsRequestedWidth(): TaoWindowTestCase {
        val widthsPx = CopyOnWriteArrayList<Int>()
        val densityHolder = AtomicReference(0f)
        return TaoWindowTestCase(
            name = "#890 dialog wrap-content height: first pass gets the requested width",
            paintDefaultBackground = false,
            dialogSize = DpSize(ISSUE_890_WIDTH_DP.dp, Dp.Unspecified),
            dialogContent = {
                densityHolder.set(LocalDensity.current.density)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(CONTENT_HEIGHT_DP.dp)
                        .onGloballyPositioned { widthsPx += it.size.width },
                )
            },
        ) {
            val dialog = checkNotNull(dialogWindow) { "dialog window never published" }
            awaitUntil("dialog shown", detail = { "widths=$widthsPx" }) {
                firstShow.containsKey(dialog.handle) && widthsPx.isNotEmpty()
            }
            val expected = ISSUE_890_WIDTH_DP * densityHolder.get()
            val wrong = widthsPx.filter { abs(it - expected) > 2f }
            check(wrong.isEmpty()) {
                "content measured at widths $widthsPx px, " +
                    "expected ${expected}px (${ISSUE_890_WIDTH_DP}dp × ${densityHolder.get()})"
            }
        }
    }

    /**
     * #890 — `fillMaxSize` content under a wrap-content height measures to a
     * zero height, which the wrap waited for in vain: the dialog was held
     * hidden for the whole 1 s fallback, then shown with its content still 0 px
     * tall. It must show at once, and the content must fill it.
     */
    private fun dialogWrapContentFillMaxSizeShowsAtOnce(): TaoWindowTestCase {
        val composedAt = AtomicLong(0)
        val contentHeightPx = AtomicInteger(-1)
        return TaoWindowTestCase(
            name = "#890 dialog wrap-content height with fillMaxSize content shows at once",
            paintDefaultBackground = false,
            dialogSize = DpSize(ISSUE_890_WIDTH_DP.dp, Dp.Unspecified),
            dialogContent = {
                composedAt.compareAndSet(0, System.nanoTime())
                Box(Modifier.fillMaxSize().onGloballyPositioned { contentHeightPx.set(it.size.height) })
            },
        ) {
            val dialog = checkNotNull(dialogWindow) { "dialog window never published" }
            awaitUntil("dialog shown", detail = { "height=${contentHeightPx.get()}" }) {
                firstShow.containsKey(dialog.handle)
            }
            val shownAfterMs = (checkNotNull(firstShow[dialog.handle]).atNanos - composedAt.get()) / 1_000_000
            check(shownAfterMs < MAX_SHOW_DELAY_MS) {
                "shown ${shownAfterMs}ms after its content composed, expected under ${MAX_SHOW_DELAY_MS}ms"
            }
            awaitUntil(
                "fillMaxSize content fills the dialog",
                detail = { "content=${contentHeightPx.get()}px bounds=${dialog.outerBoundsPx()?.toList()}" },
            ) {
                contentHeightPx.get() > 0
            }
        }
    }

    private suspend fun TaoWindowTestScope.assertMappedAndStaysAtWrapSize(target: TaoWindow) {
        awaitUntil(
            "window shown and settled at its wrap-content size",
            detail = { "bounds=${target.outerBoundsPx()?.toList()}" },
        ) {
            firstShow.containsKey(target.handle) && target.outerBoundsPx()?.wrapsContent(target) == true
        }
        val atShow = checkNotNull(firstShow[target.handle])
        val shownBounds =
            checkNotNull(atShow.bounds) { "shown before its native window existed — at the creation fallback size" }
        check(atShow.wrapSettled && shownBounds.wrapsContent(target)) {
            "shown at ${shownBounds[3] / target.scaleFactor}dp tall (wrap size applied: ${atShow.wrapSettled}), " +
                "expected the wrap-content height (${CONTENT_HEIGHT_DP}dp + chrome)"
        }
        // The size must not drift back to the fallback once settled (#532: it
        // jumped to 600dp later on, when the pointer left the dialog).
        delay(STABILITY_HOLD_MILLIS)
        val after = checkNotNull(target.outerBoundsPx())
        check(after.wrapsContent(target)) { "drifted to ${after[3] / target.scaleFactor}dp tall after settling" }
    }

    @Composable
    private fun RedBox() {
        Box(
            modifier =
                Modifier
                    .size(WRAP_WIDTH_DP.dp, CONTENT_HEIGHT_DP.dp)
                    .background(Color.Red),
        )
    }

    /** Whether `[x, y, w, h]` outer bounds are the content height plus at most the platform chrome. */
    private fun LongArray.wrapsContent(window: TaoWindow): Boolean {
        val heightDp = this[3] / window.scaleFactor
        return heightDp in CONTENT_HEIGHT_DP..(CONTENT_HEIGHT_DP + MAX_CHROME_DP)
    }

    private fun TaoWindowTestScope.workArea(): LongArray {
        val wa = TaoMonitors.primary(window).workAreaPx
        return longArrayOf(wa.left.toLong(), wa.top.toLong(), wa.width.toLong(), wa.height.toLong())
    }

    /** Whether the centres of two `[x, y, w, h]` rects are within [tolerancePx] on both axes. */
    private fun centresMatch(
        a: LongArray,
        b: LongArray,
        tolerancePx: Float,
    ): Boolean {
        val dx = (a[0] + a[2] / 2.0) - (b[0] + b[2] / 2.0)
        val dy = (a[1] + a[3] / 2.0) - (b[1] + b[3] / 2.0)
        return abs(dx) <= tolerancePx && abs(dy) <= tolerancePx
    }

    private val isNativeWayland: Boolean
        get() {
            val forcedX11 =
                System.getenv("GDK_BACKEND")?.split(',')?.firstOrNull() == "x11" ||
                    System.getenv("NUCLEUS_TAO_LINUX_RENDERER").orEmpty().equals("x11", ignoreCase = true)
            return System.getenv("WAYLAND_DISPLAY") != null && !forcedX11
        }

    private const val WRAP_WIDTH_DP = 300f
    private const val WRAP_HEIGHT_DP = 300f
    private const val CONTENT_HEIGHT_DP = 137f
    private const val STABILITY_HOLD_MILLIS = 1_000L
    private const val ISSUE_890_WIDTH_DP = 650f

    // Well under the 1 s wrap fallback the #890 dialog waited for.
    private const val MAX_SHOW_DELAY_MS = 500L

    // Title bar + Linux CSD shadow / macOS traffic-light chrome.
    private const val MAX_CHROME_DP = 220f

    // Outer-vs-inner chrome is not symmetric (title bar, Win32 invisible
    // borders): the un-fixed offsets are hundreds of dp, this is well under.
    private const val CENTRE_TOLERANCE_DP = 40f
}
