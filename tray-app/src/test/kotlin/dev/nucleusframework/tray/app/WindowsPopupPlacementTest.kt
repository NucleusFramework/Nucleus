package dev.nucleusframework.tray.app

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [windowsPopupOrigin] against every DPI scale and taskbar edge (LTR and RTL) a real machine can
 * have, the anchor computed exactly like `tray_get_notification_icons_position` does. The E2E
 * suite only runs on the dev machine's 100 % / bottom taskbar; this covers the rest, seeded.
 */
class WindowsPopupPlacementTest {
    private enum class Edge { BOTTOM, TOP, LEFT, RIGHT }

    private data class Rect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width get() = right - left
        val height get() = bottom - top
        val centerX get() = (left + right) / 2

        fun scaled(scale: Float) =
            Rect(
                (left / scale).toInt(),
                (top / scale).toInt(),
                (right / scale).toInt(),
                (
                    bottom /
                        scale
                ).toInt(),
            )

        fun intersects(o: Rect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    }

    private data class Setup(
        val scale: Float,
        val edge: Edge,
        val rtl: Boolean,
        val workPx: Rect,
        val iconPx: Rect,
    )

    private fun setup(
        scale: Float,
        edge: Edge,
        rtl: Boolean,
        screenW: Int = 2560,
        screenH: Int = 1440,
    ): Setup {
        val bar = (48 * scale).toInt()
        // Shell_NotifyIconGetRect returns the whole button: the taskbar's thickness across.
        val icon = (32 * scale).toInt()
        val trail = (120 * scale).toInt()
        return when (edge) {
            Edge.BOTTOM -> {
                val x = if (rtl) trail else screenW - trail
                Setup(scale, edge, rtl, Rect(0, 0, screenW, screenH - bar), Rect(x, screenH - bar, x + icon, screenH))
            }
            Edge.TOP -> {
                val x = if (rtl) trail else screenW - trail
                Setup(scale, edge, rtl, Rect(0, bar, screenW, screenH), Rect(x, 0, x + icon, bar))
            }
            Edge.LEFT ->
                Setup(
                    scale,
                    edge,
                    rtl,
                    Rect(bar, 0, screenW, screenH),
                    Rect(
                        0,
                        screenH - trail,
                        bar,
                        screenH - trail + icon,
                    ),
                )
            Edge.RIGHT ->
                Setup(
                    scale,
                    edge,
                    rtl,
                    Rect(0, 0, screenW - bar, screenH),
                    Rect(screenW - bar, screenH - trail, screenW, screenH - trail + icon),
                )
        }
    }

    /** What the native side reports: icon center x, bottom edge in the top half, top edge otherwise. */
    private fun anchor(
        s: Setup,
        screenH: Int = 1440,
    ): Pair<Int, Int> = s.iconPx.centerX to (if (s.iconPx.top < screenH / 2) s.iconPx.bottom else s.iconPx.top)

    private fun place(
        s: Setup,
        w: Int,
        h: Int,
        dx: Int = 0,
        dy: Int = 0,
    ): Rect {
        val work = s.workPx.scaled(s.scale)
        val (ax, ay) = anchor(s)
        val (x, y) =
            windowsPopupOrigin(
                ax,
                ay,
                s.scale,
                ScreenRect(work.left, work.top, work.width, work.height),
                w,
                h,
                dx,
                dy,
            )
        return Rect(x, y, x + w, y + h)
    }

    private val scales = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 3f, 3.5f)

    /** What is wrong with a [w] x [h] popup placed for [s]; empty when nothing is. */
    private fun problems(
        s: Setup,
        w: Int,
        h: Int,
    ): List<String> {
        val work = s.workPx.scaled(s.scale)
        val icon = s.iconPx.scaled(s.scale)
        // A popup larger than the work area is the other test's business (top-left kept).
        if (w > work.width || h > work.height) return emptyList()
        val p = place(s, w, h)
        val tag = "scale=${s.scale} edge=${s.edge} rtl=${s.rtl} size=${w}x$h popup=$p icon=$icon work=$work"
        val found = mutableListOf<String>()
        if (p.left < work.left || p.top < work.top || p.right > work.right || p.bottom > work.bottom) {
            found += "outside work area: $tag"
        }
        if (p.intersects(icon)) found += "covers the icon: $tag"
        val againstTaskbar =
            when (s.edge) {
                Edge.BOTTOM -> icon.top - p.bottom in 0..2
                Edge.TOP -> p.top - icon.bottom in 0..2
                Edge.LEFT -> p.left == work.left
                Edge.RIGHT -> p.right == work.right
            }
        if (!againstTaskbar) found += "not against the taskbar: $tag"
        val horizontal = s.edge == Edge.BOTTOM || s.edge == Edge.TOP
        val centered = abs(p.centerX - icon.centerX) <= 1 || p.left == work.left || p.right == work.right
        if (horizontal && !centered) found += "not centered on the icon: $tag"
        return found
    }

    @Test
    fun `every scale and taskbar edge anchors the popup against the icon`() {
        val sizes = listOf(300 to 200, 64 to 64, 420 to 360, 1 to 1, 800 to 600)
        val failures =
            scales.flatMap { scale ->
                Edge.entries.flatMap { edge ->
                    listOf(false, true).flatMap { rtl ->
                        val s = setup(scale, edge, rtl)
                        sizes.flatMap { (w, h) -> problems(s, w, h) }
                    }
                }
            }
        if (failures.isNotEmpty()) fail("${failures.size} placements wrong:\n" + failures.take(20).joinToString("\n"))
    }

    @Test
    fun `seeded sizes and offsets never leave the work area`() {
        val seed = System.getProperty("nucleus.tray.torture.seed")?.toLongOrNull() ?: System.nanoTime()
        println("[torture] placement seed=$seed")
        val random = Random(seed)
        repeat(20_000) {
            val s =
                setup(
                    scales.random(random),
                    Edge.entries.random(random),
                    random.nextBoolean(),
                    screenW = random.nextInt(800, 7680),
                    screenH = random.nextInt(600, 4320),
                )
            val work = s.workPx.scaled(s.scale)
            val w = random.nextInt(1, 6000)
            val h = random.nextInt(1, 6000)
            val p = place(s, w, h, random.nextInt(-3000, 3000), random.nextInt(-3000, 3000))
            val tag = "seed=$seed case=$it $s size=${w}x$h popup=$p work=$work"
            if (w <= work.width) {
                assertTrue(p.left >= work.left && p.right <= work.right, "horizontally outside: $tag")
            } else {
                assertTrue(p.left == work.left, "oversized popup hides its left edge: $tag")
            }
            if (h <= work.height) {
                assertTrue(p.top >= work.top && p.bottom <= work.bottom, "vertically outside: $tag")
            } else {
                assertTrue(p.top == work.top, "oversized popup hides its top edge: $tag")
            }
        }
    }
}
