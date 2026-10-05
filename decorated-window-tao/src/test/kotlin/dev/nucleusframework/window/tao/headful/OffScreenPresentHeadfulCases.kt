package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsNativeViewBridge
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A window whose frame is presented while part of it lies outside every
 * display must still hold that part's pixels.
 *
 * On Windows the compositor keeps one copy of each window and draws the
 * screen, the taskbar thumbnail, the Alt+Tab card and the window's own
 * animations from it. A present that only reaches the on-screen part of the
 * window leaves the rest of that copy unpainted: the window shows a white
 * band when it is dragged into view, and its thumbnail shows it even if it
 * never is. Nothing about the scene is wrong — the frame was rendered whole
 * — so only the pixels can tell.
 *
 * The cases park the window mostly past the right edge of the desktop,
 * present one frame of a known colour there, then read the window back:
 * through `PrintWindow(PW_RENDERFULLCONTENT)` (the compositor's copy, the
 * thumbnail's source) while it is still off-screen, and from the screen
 * once it has been moved into view without drawing anything new.
 */
internal object OffScreenPresentHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            aFramePresentedOffScreenIsWholeOnceInView(),
            aTransparentWindowStillShowsWhatIsBehindIt(),
        )

    private fun skipReason(): String? =
        when {
            Platform.Current != Platform.Windows -> "Windows only: DWM's copy of a window is what the case reads"
            !NativeTaoWindowsNativeViewBridge.isLoaded -> "nucleus_tao_windows_native_view not loaded"
            else -> null
        }

    private fun aFramePresentedOffScreenIsWholeOnceInView(): TaoWindowTestCase {
        val fill = mutableStateOf(BEFORE)
        val drawnFills = AtomicInteger()
        return TaoWindowTestCase(
            name = "windows a frame presented off-screen is whole once moved into view",
            skip = ::skipReason,
            size = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp),
            // The fill is the whole content: nothing else may cover or share it.
            paintDefaultBackground = false,
            content = {
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        drawRect(fill.value)
                        if (fill.value == AFTER) drawnFills.incrementAndGet()
                    },
                )
            },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            settle()
            val hwnd = window.nativeHandle
            check(hwnd != 0L) { "no HWND" }
            window.setAlwaysOnTop(true)
            window.focus()
            settle()
            // The case's own first frame must be readable, or every verdict
            // below is about something else.
            val first = printClient(hwnd)
            dumpIfRequested("first", first)
            val firstCoverage = first?.coverage(EDGE_INSET_PX, BEFORE)
            check(firstCoverage != null && firstCoverage.ratio >= MIN_COVERAGE) {
                "the first frame, fully on-screen, does not read back: ${firstCoverage?.describe()}"
            }

            // The right edge of the whole desktop, so nothing lies beyond it.
            val monitors = TaoMonitors.all(window)
            val edge = monitors.maxBy { it.boundsPx.right }
            val desktopRight = edge.boundsPx.right
            val y = edge.workAreaPx.top + PARK_MARGIN_PX
            val width = requireNotNull(bounds())[2].toInt()
            val parkedX = desktopRight - VISIBLE_WHEN_PARKED_PX
            window.setOuterPositionPx(parkedX, y)
            awaitUntil("window parked past the desktop edge") { bounds()?.get(0)?.toInt() == parkedX }
            settle()

            // One frame, presented while most of the window is off-screen.
            fill.value = AFTER
            awaitUntil("the new fill was drawn") { drawnFills.get() > 0 }
            settle(PRESENT_SETTLE_MILLIS)

            // The part of the client area that was off-screen when it was
            // presented, in client px (the client area starts at the window
            // frame on the decorated window: the caption is client-drawn).
            val offScreenFromX = desktopRight - parkedX + EDGE_INSET_PX

            val parkedCopy = printClient(hwnd)
            val parkedCoverage = parkedCopy?.coverage(offScreenFromX)

            // Into view, read before the loop runs again: a drag outruns the
            // repaint the move schedules, so what the user sees while dragging
            // is what was presented while off-screen. The move is synchronous
            // (SetWindowPos on this thread) and so are both reads; the loop is
            // held for the compositor to show the moved window.
            val inViewX = edge.workAreaPx.left + PARK_MARGIN_PX
            window.setOuterPositionPx(inViewX, y)
            Thread.sleep(COMPOSE_WAIT_MILLIS)
            val rect = requireNotNull(bounds())
            check(rect[0].toInt() == inViewX) { "the window did not move into view: x=${rect[0]}" }
            val inViewCopy = printClient(hwnd)
            val screen = captureScreen(rect)

            dumpIfRequested("parked", parkedCopy)
            dumpIfRequested("in-view", inViewCopy)
            dumpIfRequested("screen", screen)
            val inViewCoverage = inViewCopy?.coverage(offScreenFromX)
            val screenCoverage = screen?.coverage(offScreenFromX)
            println(
                "[offscreen] desktopRight=$desktopRight parkedX=$parkedX " +
                    "dwmCopyParked=${parkedCoverage?.describe()} dwmCopyInView=${inViewCoverage?.describe()} " +
                    "screen=${screenCoverage?.describe()}",
            )
            val failures =
                listOfNotNull(
                    parkedCoverage.failure("DWM's copy (taskbar thumbnail) while parked off-screen"),
                    inViewCoverage.failure("DWM's copy right after moving into view"),
                    screenCoverage.failure("the screen right after moving into view"),
                )
            check(failures.isEmpty()) { failures.joinToString("; ") }
        }
    }

    /**
     * The present path has to keep per-pixel alpha: a transparent window's
     * clear pixels show whatever is behind it, not black and not a fill. The
     * case grabs the same screen region with the window over it and with the
     * window moved away; the clear half must match between the two, the
     * opaque half must not.
     */
    private fun aTransparentWindowStillShowsWhatIsBehindIt(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "windows a transparent window still shows what is behind it",
            skip = ::skipReason,
            transparent = true,
            paintDefaultBackground = false,
            size = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp),
            content = {
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        drawRect(AFTER, topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2, size.height))
                    },
                )
            },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            window.setAlwaysOnTop(true)
            window.focus()
            settle(TRANSPARENT_SETTLE_MILLIS)
            val rect = requireNotNull(bounds())
            val over = requireNotNull(captureScreen(rect)) { "the screen could not be read" }
            val monitor = TaoMonitors.all(window).maxBy { it.boundsPx.right }
            window.setOuterPositionPx(monitor.boundsPx.right + PARK_MARGIN_PX, rect[1].toInt())
            settle(PRESENT_SETTLE_MILLIS)
            val behind = requireNotNull(captureScreen(rect)) { "the screen could not be read" }
            window.setOuterPositionPx(rect[0].toInt(), rect[1].toInt())
            dumpIfRequested("transparent-over", over)
            dumpIfRequested("transparent-behind", behind)

            // Inset well past the window frame and the halves' seam.
            val inset = (FRAME_INSET_DP * window.scaleFactor.coerceAtLeast(1f)).roundToInt()
            val clearHalf = sameRatio(over, behind, inset, over.width / 2 - inset, inset)
            val opaqueHalf = sameRatio(over, behind, over.width / 2 + inset, over.width - inset, inset)
            println("[transparent] clearHalfSameAsBehind=$clearHalf opaqueHalfSameAsBehind=$opaqueHalf")
            check(clearHalf >= MIN_COVERAGE) {
                "the clear half of a transparent window hides what is behind it: only $clearHalf of it matches"
            }
            check(opaqueHalf < 1 - MIN_COVERAGE) { "the opaque half is not drawn: $opaqueHalf of it is the backdrop" }
        }

    /** Share of the pixels in columns [fromX, toX) (rows inset by [inset]) that are equal in [a] and [b]. */
    private fun sameRatio(
        a: ClientPixels,
        b: ClientPixels,
        fromX: Int,
        toX: Int,
        inset: Int,
    ): Double {
        var same = 0
        var total = 0
        for (y in inset until a.height - inset step SAMPLE_STEP_PX) {
            for (x in fromX until toX step SAMPLE_STEP_PX) {
                total++
                if (a.pixel(x, y).matches(b.pixel(x, y))) same++
            }
        }
        return if (total == 0) 0.0 else same.toDouble() / total
    }

    /** `-Dnucleus.tao.headful.offscreenDumpDir=<dir>` writes every capture there as a PNG. */
    private fun dumpIfRequested(
        name: String,
        pixels: ClientPixels?,
    ) {
        val dir = System.getProperty("nucleus.tao.headful.offscreenDumpDir") ?: return
        if (pixels == null) return
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, pixels.width, pixels.height, pixels.argb, pixels.offset, pixels.width)
        java.io.File(dir).mkdirs()
        javax.imageio.ImageIO.write(image, "png", java.io.File(dir, "$name.png"))
    }

    /**
     * The screen under the window's outer [rect] (physical px). Not the AWT
     * Robot: it works in its own DPI space and omits layered windows.
     */
    private fun captureScreen(rect: LongArray): ClientPixels? =
        NativeTaoWindowsNativeViewBridge
            .nativeDiagCaptureScreen(rect[0].toInt(), rect[1].toInt(), rect[2].toInt(), rect[3].toInt())
            ?.let { ClientPixels(width = it[0], height = it[1], argb = it, offset = 2) }

    private fun printClient(hwnd: Long): ClientPixels? =
        NativeTaoWindowsNativeViewBridge.nativeDiagPrintClient(hwnd)?.let {
            ClientPixels(width = it[0], height = it[1], argb = it, offset = 2)
        }

    private class ClientPixels(
        val width: Int,
        val height: Int,
        val argb: IntArray,
        val offset: Int,
    ) {
        fun pixel(
            x: Int,
            y: Int,
        ): Int = argb[offset + y * width + x]

        /** How much of the client from [fromX] rightwards (inset from the edges) shows [color]. */
        fun coverage(
            fromX: Int,
            color: Color = AFTER,
        ): Coverage {
            var matching = 0
            var total = 0
            var firstMiss: Int? = null
            for (y in EDGE_INSET_PX until height - EDGE_INSET_PX step SAMPLE_STEP_PX) {
                for (x in fromX until width - EDGE_INSET_PX step SAMPLE_STEP_PX) {
                    val pixel = argb[offset + y * width + x]
                    total++
                    if (pixel.matches(color.toArgb())) {
                        matching++
                    } else if (firstMiss == null) {
                        firstMiss = pixel
                    }
                }
            }
            return Coverage(matching, total, firstMiss)
        }
    }

    private class Coverage(
        val matching: Int,
        val total: Int,
        val firstMiss: Int?,
    ) {
        val ratio: Double get() = if (total == 0) 0.0 else matching.toDouble() / total

        fun describe(): String =
            "${"%.3f".format(ratio)} ($matching/$total" +
                (firstMiss?.let { ", e.g. #%08X".format(it) } ?: "") + ")"
    }

    private fun Coverage?.failure(where: String): String? =
        when {
            this == null -> "$where could not be read"
            total == 0 -> "$where had no pixel to sample"
            ratio < MIN_COVERAGE -> "$where shows the presented frame on only ${describe()} of the off-screen part"
            else -> null
        }

    private fun Int.matches(expected: Int): Boolean =
        channelClose(this shr 16, expected shr 16) &&
            channelClose(this shr 8, expected shr 8) &&
            channelClose(this, expected)

    private fun channelClose(
        a: Int,
        b: Int,
    ): Boolean = abs((a and 0xFF) - (b and 0xFF)) <= CHANNEL_TOLERANCE

    private val BEFORE = Color(0xFF8E24AA)
    private val AFTER = Color(0xFF1E88E5)

    private const val WINDOW_W_DP = 480
    private const val FRAME_INSET_DP = 16
    private const val WINDOW_H_DP = 320
    private const val VISIBLE_WHEN_PARKED_PX = 120
    private const val PARK_MARGIN_PX = 80
    private const val EDGE_INSET_PX = 8
    private const val SAMPLE_STEP_PX = 4
    private const val PRESENT_SETTLE_MILLIS = 400L
    private const val COMPOSE_WAIT_MILLIS = 150L
    private const val TRANSPARENT_SETTLE_MILLIS = 1_000L
    private const val CHANNEL_TOLERANCE = 6
    private const val MIN_COVERAGE = 0.98
}
