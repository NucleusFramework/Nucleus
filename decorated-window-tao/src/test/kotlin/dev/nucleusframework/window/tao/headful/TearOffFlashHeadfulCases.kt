package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.ExperimentalNucleusApi
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsNativeViewBridge
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A tab torn into a window of its own never shows unpainted pixels.
 *
 * The torn window is created where the pointer released the tab and appears
 * at once; anything DWM composes for it before its first frame lands is the
 * redirection surface nobody painted — white. The screen around the drop is
 * filmed from the release on, and a pixel counts as a flash when it is white
 * in some frame although it is neither white in the window's settled frame
 * nor on the desktop behind (both read from the same film).
 */
@OptIn(ExperimentalNucleusApi::class)
internal object TearOffFlashHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(tearOffShowsNoUnpaintedPixels())

    /**
     * Opt-in (`-Dnucleus.tao.headful.tearOffFlash=true`): the full-display film
     * is too slow to catch a one-composition flash reliably, and the robot drag
     * misses its start when the machine is in use.
     */
    private fun skipReason(): String? =
        when {
            System.getProperty("nucleus.tao.headful.tearOffFlash") != "true" ->
                "opt-in: -Dnucleus.tao.headful.tearOffFlash=true (see KDoc)"
            Platform.Current != Platform.Windows -> "Windows only: films DWM's composition"
            !NativeTaoWindowsNativeViewBridge.isLoaded -> "nucleus_tao_windows_native_view not loaded"
            else -> workspaceSkipReason()
        }

    private fun tearOffShowsNoUnpaintedPixels(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture()
        return TaoWindowTestCase(
            name = "windows a tab torn into its own window shows no unpainted pixels",
            skip = ::skipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
        ) {
            val first = awaitTabWindows(fixture, "Alpha", "Beta")
            settle()
            val beta = fixture.tabId("Beta")
            val grab = requireNotNull(fixture.tabCenterPx("Beta")) { "Beta published no slot" }
            val strip = requireNotNull(fixture.stripRectPx(requireNotNull(fixture.groupOf("Beta"))))
            val dropOut = Offset(strip.center.x, strip.bottom + TAB_DROP_FAR_PX)
            // The whole display: the torn window lands where the workspace places
            // it, not necessarily around the pointer.
            val screen = TaoMonitors.forWindow(first).boundsPx
            val region = intArrayOf(screen.left, screen.top, screen.width, screen.height)
            val reference = grab(region)

            // The user's gesture, with the real mouse: the press also brings the
            // source window to the foreground, so the torn window opens in front.
            val workspace = fixture.workspace
            checkNotNull(robotPressAndDrag(grab, dropOut, first.scaleFactor)) {
                HeadfulRobot.unavailableReason ?: "the drag could not be injected"
            }
            awaitUntil("the press-drag started a drag of Beta") { workspace.draggedTab?.id == beta }
            settle()
            val film = Film(region)
            film.start()
            checkNotNull(robotRelease()) { "robot became unavailable mid-case" }
            awaitUntil("a second window holds Beta on its own") {
                workspace.groups.size == 2 && fixture.groupOf("Beta")?.ids == listOf(beta)
            }
            awaitUntil("the torn-off window is mapped") {
                val window = fixture.groupOf("Beta")?.window ?: return@awaitUntil false
                window !== first && window.hasRealFramePx()
            }
            settle(SETTLE_AFTER_TEAR_MILLIS)
            film.stop()
            val torn = requireNotNull(requireNotNull(fixture.groupOf("Beta")?.window).outerBoundsPx())
            film.assertNoFlash(
                requireNotNull(reference) { "the screen could not be read" },
                window =
                    intArrayOf(
                        torn[0].toInt() - region[0],
                        torn[1].toInt() - region[1],
                        torn[2].toInt(),
                        torn[3].toInt(),
                    ),
            )
        }
    }

    private fun grab(region: IntArray): IntArray? =
        NativeTaoWindowsNativeViewBridge
            .nativeDiagCaptureScreen(region[0], region[1], region[2], region[3])
            ?.let { it.copyOfRange(2, it.size) }

    private class Film(
        private val region: IntArray,
    ) {
        private val running = AtomicBoolean(false)
        private val frames = ArrayList<IntArray>()
        private var grabber: Thread? = null

        fun start() {
            running.set(true)
            grabber =
                thread(name = "tear-off-film") {
                    while (running.get() && frames.size < MAX_FRAMES) {
                        grab(region)?.let { synchronized(frames) { frames += it } }
                    }
                }
        }

        fun stop() {
            running.set(false)
            grabber?.join()
        }

        /** [window]: the torn window's outer rect in film px, `[x, y, w, h]`. */
        fun assertNoFlash(
            reference: IntArray,
            window: IntArray,
        ) {
            val settled = synchronized(frames) { frames.lastOrNull() } ?: error("no frame filmed")
            System.getProperty("nucleus.tao.headful.offscreenDumpDir")?.let { dir ->
                val w = region[2]
                val h = region[3]
                val pick =
                    synchronized(frames) {
                        listOf(0, frames.size / 4, frames.size / 2, frames.size - 1).map {
                            it to
                                frames[it]
                        }
                    }
                java.io.File(dir).mkdirs()
                for ((n, f) in pick) {
                    val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    img.setRGB(0, 0, w, h, f, 0, w)
                    javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "tear-$n.png"))
                }
            }
            // Sampled grid inside the torn window where neither its settled frame
            // nor the desktop behind it is white.
            val w = region[2]
            val watched = ArrayList<Int>()
            for (y in maxOf(0, window[1]) until minOf(region[3], window[1] + window[3]) step SAMPLE_STEP) {
                for (x in maxOf(0, window[0]) until minOf(w, window[0] + window[2]) step SAMPLE_STEP) {
                    val i = y * w + x
                    if (!settled[i].isWhite() && !reference[i].isWhite()) watched += i
                }
            }
            var flashFrames = 0
            var worst = 0
            val examples = ArrayList<String>()
            synchronized(frames) {
                for ((n, frame) in frames.withIndex()) {
                    val white = watched.count { frame[it].isWhite() }
                    if (white > worst) worst = white
                    if (white > FLASH_MIN_PX) {
                        flashFrames++
                        if (examples.size < MAX_EXAMPLES) examples += "#$n: $white px"
                    }
                }
            }
            println(
                "[tear-off] window=${window.toList()} frames=${frames.size} watched=${watched.size} " +
                    "flashFrames=$flashFrames worst=$worst $examples",
            )
            check(frames.size >= MIN_FRAMES) { "only ${frames.size} frames filmed" }
            check(flashFrames == 0) {
                "$flashFrames of ${frames.size} frames showed unpainted (white) pixels, worst $worst px $examples"
            }
        }
    }

    private fun Int.isWhite(): Boolean =
        (this shr 16 and 0xFF) >= WHITE_MIN && (this shr 8 and 0xFF) >= WHITE_MIN && (this and 0xFF) >= WHITE_MIN

    private const val SAMPLE_STEP = 7
    private const val SETTLE_AFTER_TEAR_MILLIS = 800L
    private const val MAX_FRAMES = 400
    private const val MIN_FRAMES = 10
    private const val MAX_EXAMPLES = 8
    private const val FLASH_MIN_PX = 20
    private const val WHITE_MIN = 0xF0
}
