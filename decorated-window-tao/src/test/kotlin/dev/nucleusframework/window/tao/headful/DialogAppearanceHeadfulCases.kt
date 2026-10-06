package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Measures the appearance of a Compose `Dialog` as the user sees it — pixels
 * grabbed from the screen while it opens — once drawn in the window's own
 * scene and once as a native popup layer, and compares the two.
 *
 * `Dialog.skiko.kt` animates a dialog in over 200 ms: the scrim fades in, the
 * content fades from 20 % alpha, scales up from 95 % and slides up 10 dp.
 * Nothing in the layer API says so; a native layer only sees `scrimColor`
 * writes and a `boundsInWindow`. The only way to know that a real OS surface
 * reproduces the in-scene look is to film both and compare the curves: when
 * the dialog first shows, how far it slides, how long the scrim and the
 * content take to settle.
 *
 * The three cases run in order and share [Sample]s through [measured]; the
 * first two film, the third compares and prints both curves side by side so a
 * difference can be read off the log.
 */
internal object DialogAppearanceHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            film(native = false),
            film(native = true),
            compare(),
            film(native = false, material = true),
            film(native = true, material = true),
            compare(material = true),
            translated(native = false),
            translated(native = true),
            compareTranslated(),
        )

    /** One screen grab: [tMs] after the dialog was shown. */
    internal class Sample(
        val tMs: Long,
        /** Red channel of the white background under the scrim (255 = no scrim). */
        val scrimRed: Int,
        /** Top and bottom of the dialog's colour on the centre column, or null when not visible. */
        val dialogTop: Int?,
        val dialogBottom: Int?,
        /** Blue minus red at the dialog's centre; grows as the dialog fades in. */
        val blueness: Int,
    )

    internal class Curve(
        val all: List<Sample>,
        /** When the dialog was asked to close; samples from here on film the disappearance. */
        val hideAtMs: Long,
    ) {
        /** The appearance: from the show request until the hide request. */
        val samples: List<Sample> get() = all.filter { it.tMs < hideAtMs }

        /** The disappearance: from the hide request on. */
        val hiding: List<Sample> get() = all.filter { it.tMs >= hideAtMs }
        val visible: List<Sample> get() = samples.filter { it.dialogTop != null }

        /** First moment after the hide request where the dialog started to change. */
        val hideStartMs: Long? get() = hideStartWindow?.last

        /**
         * When the dialog started to change after the hide request, as an
         * interval, in ms after the request: from the last grab that still
         * showed it at rest to the first that did not. Grabs are tens of ms
         * apart on a slow host, so the first changed grab alone says only that
         * the change came before it — a film whose grab after the request was
         * 50 ms late read 90 ms where its counterpart, grabbed densely, read 30
         * (macOS CI). Compared as intervals, see `compare`.
         */
        val hideStartWindow: LongRange?
            get() {
                val rest = hiding.firstOrNull() ?: return null
                return eventWindow {
                    it.dialogTop != rest.dialogTop ||
                        it.blueness != rest.blueness ||
                        it.scrimRed != rest.scrimRed
                }
            }

        /**
         * The interval, in ms after the hide request, in which [happened] first
         * became true: between the last grab of [hiding] where it was false (or
         * the request itself) and the first where it was true.
         */
        private fun eventWindow(happened: (Sample) -> Boolean): LongRange? {
            val index = hiding.indexOfFirst(happened)
            if (index < 0) return null
            val before = if (index == 0) hideAtMs else hiding[index - 1].tMs
            return (before - hideAtMs)..(hiding[index].tMs - hideAtMs)
        }

        /**
         * The smallest height the dialog's colour spanned while fading out,
         * as a fraction of its resting height. `Dialog.skiko.kt` reports a
         * zero-size `boundsInWindow` during the fade-out; a native surface that
         * followed it shrank the dialog to a square of margin around a point.
         *
         * Read over *two consecutive frames*, not one. The collapse this guards
         * against lasts the whole fade — it is where the surface now is — while
         * a lone short frame is a drawable caught mid-present, which a separate
         * OS surface can show and a scene drawing into the window canvas never
         * can. Filming a fade-out on a real compositor turns up one such frame
         * often enough (measured: heights of 1 px and of half the dialog, in
         * runs whose neighbouring frames were both full height) that the strict
         * minimum reports the compositor rather than the layer.
         */
        val hideMinHeightRatio: Float?
            get() {
                val rest = visible.lastOrNull() ?: return null
                val restHeight = (rest.dialogBottom!! - rest.dialogTop!!).coerceAtLeast(1)
                val heights =
                    hiding
                        .filter { it.dialogTop != null && it.dialogBottom != null }
                        .map { it.dialogBottom!! - it.dialogTop!! }
                if (heights.isEmpty()) return null
                val sustained =
                    if (heights.size == 1) heights.first() else heights.zipWithNext(::maxOf).min()
                return sustained.toFloat() / restHeight
            }

        /**
         * The longest the grabber went without a frame while something was
         * changing: from the show request until the appearance settled, and
         * from the hide request until the dialog was gone — each including the
         * wait for the first grab and the grab after the window's end.
         */
        val maxCaptureGapMs: Long
            get() {
                val show = gapWithin(0L, samples.map { it.tMs }, settledMs ?: hideAtMs)
                val hide = gapWithin(hideAtMs, hiding.map { it.tMs }, hideAtMs + (hideGoneMs ?: 0L))
                return maxOf(show, hide)
            }

        private fun gapWithin(
            start: Long,
            times: List<Long>,
            end: Long,
        ): Long {
            val within = times.takeWhile { it <= end }
            val span = listOf(start) + within + listOfNotNull(times.getOrNull(within.size))
            return span.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0L
        }

        /** First moment after the hide request where the dialog was gone. */
        val hideGoneMs: Long? get() = hideGoneWindow?.last

        /**
         * When the dialog was first gone, as an interval: it went between the
         * grab before [hideGoneMs] and that one. See [hideStartWindow].
         */
        val hideGoneWindow: LongRange? get() = eventWindow { it.dialogTop == null }

        /**
         * The longest the picture held still during an animation [phase], in
         * ms: from the first grab of one picture to the first grab of the next.
         *
         * A dropped frame is a picture held for longer than a refresh, so this
         * is measured in time rather than in repeated grabs. The grabber runs
         * far faster than the display (every 2–15 ms, depending on the host
         * and its load), so how many consecutive grabs repeat a frame says
         * more about the grab rate during that particular film than about
         * the renderer, and two films taken separately are not comparable by
         * that count. The picture still shown when [phase] ends is not a hold:
         * nothing came after it to measure against.
         */
        fun longestHoldMs(phase: List<Sample>): Long {
            var longest = 0L
            var since = phase.firstOrNull() ?: return 0L
            for (sample in phase.drop(1)) {
                val same =
                    sample.dialogTop == since.dialogTop &&
                        sample.dialogBottom == since.dialogBottom &&
                        sample.blueness == since.blueness &&
                        sample.scrimRed == since.scrimRed
                if (!same) {
                    longest = maxOf(longest, sample.tMs - since.tMs)
                    since = sample
                }
            }
            return longest
        }

        val showLongestHoldMs: Long
            get() {
                val end = settledMs ?: return 0L
                return longestHoldMs(visible.filter { it.tMs <= end })
            }

        val hideLongestHoldMs: Long
            get() {
                val start = hideStartMs ?: return 0L
                val end = hideGoneMs ?: return 0L
                return longestHoldMs(hiding.filter { it.tMs - hideAtMs in start..end })
            }

        /**
         * Frames from half-way through the fade-in on, which is where the
         * appearance can be compared between the two layers.
         *
         * [visible] begins at the knife-edge of the colour probe: the dialog
         * fades in over the scrim, so its first frames are detected or not
         * depending on where the sampling clock lands against
         * [DIALOG_DETECT_THRESHOLD]. Measured on both layers, that first frame
         * is bimodal — 0 ms on the runs that caught the faint start, ~60 ms on
         * the runs that did not — and every metric anchored on it inherits the
         * split, so the two films disagree whenever they land in different
         * modes. Half the settled blueness is far from that edge and names the
         * same moment of the same animation on either layer.
         */
        private val fadedIn: List<Sample> get() = visible.filter { it.blueness * 2 >= finalBlueness }

        val firstVisibleMs: Long? get() = fadedIn.firstOrNull()?.tMs
        val finalTop: Int? get() = visible.lastOrNull()?.dialogTop
        val finalBlueness: Int get() = visible.lastOrNull()?.blueness ?: 0
        val finalScrimRed: Int get() = samples.lastOrNull()?.scrimRed ?: WHITE

        /**
         * How far below its resting place the dialog was half-way in, in
         * logical px.
         *
         * Interpolated between the two grabs that straddle half the settled
         * blueness, like [scrimRamp]: the dialog moves several px between two
         * grabs early in the fade, so reading the first grab past that point
         * measured where the grab happened to land (macOS CI: in-scene 4 px
         * vs native 9 px for the same animation, both ~10 px interpolated).
         */
        val slideInPx: Int?
            get() {
                val last = finalTop ?: return null
                val half = finalBlueness / 2.0
                val after = visible.indexOfFirst { it.blueness >= half }
                if (after < 0) return null
                val b = visible[after]
                val bTop = b.dialogTop ?: return null
                val topAtHalf =
                    if (after == 0) {
                        bTop.toDouble()
                    } else {
                        val a = visible[after - 1]
                        val aTop = a.dialogTop ?: bTop
                        val k = (half - a.blueness) / (b.blueness - a.blueness)
                        aTop + k * (bTop - aTop)
                    }
                return (topAtHalf - last).roundToInt()
            }

        /** How long the appearance animated on screen, from its first frame to its last change. */
        val animationMs: Long?
            get() {
                val first = firstVisibleMs ?: return null
                val end = settledMs ?: return null
                return end - first
            }

        /** First moment after which position, content alpha and scrim all stay at their final values. */
        val settledMs: Long?
            get() {
                val top = finalTop ?: return null
                val settled =
                    visible.takeLastWhile {
                        abs(it.dialogTop!! - top) <= SETTLE_PX &&
                            abs(it.blueness - finalBlueness) <= SETTLE_COLOR &&
                            abs(it.scrimRed - finalScrimRed) <= SETTLE_COLOR
                    }
                return settled.firstOrNull()?.tMs
            }

        /**
         * How much darker the scrim still got after the dialog was half faded in.
         *
         * Read at the same moment as [fadedIn], and for the same reason: the
         * first visible frame lands wherever the sampling clock does, and the
         * scrim moves ~40 levels between two grabs early in the fade, so a ramp
         * anchored on it measured the clock (in-scene 115 vs native 88 on one
         * run, 94 vs 117 on the next). The scrim is interpolated between the
         * two grabs that straddle half the settled blueness, which names the
         * same instant of the animation however the grabs fall.
         */
        val scrimRamp: Int
            get() {
                val half = finalBlueness / 2.0
                val after = samples.indexOfFirst { it.blueness >= half }
                if (after < 0) return 0
                val b = samples[after]
                val scrimAtHalf =
                    if (after == 0) {
                        b.scrimRed.toDouble()
                    } else {
                        val a = samples[after - 1]
                        val k = (half - a.blueness) / (b.blueness - a.blueness)
                        a.scrimRed + k * (b.scrimRed - a.scrimRed)
                    }
                return (scrimAtHalf - finalScrimRed).roundToInt()
            }

        fun table(): String =
            buildString {
                appendLine("    t(ms)  scrimR  top  bottom  blueness   (hide requested at ${hideAtMs}ms)")
                for (s in all) {
                    appendLine(
                        "    %5d  %6d  %4s  %6s  %8d".format(
                            s.tMs,
                            s.scrimRed,
                            s.dialogTop?.toString() ?: "-",
                            s.dialogBottom?.toString() ?: "-",
                            s.blueness,
                        ),
                    )
                }
            }

        fun summary(): String =
            "show: firstVisible=${firstVisibleMs}ms settled=${settledMs}ms animated=${animationMs}ms " +
                "slideIn=${slideInPx}px " +
                "scrimRamp=$scrimRamp finalScrimRed=$finalScrimRed finalBlueness=$finalBlueness " +
                "hold=${showLongestHoldMs}ms | hide: start=${hideStartMs}ms gone=${hideGoneMs}ms " +
                "minHeight=${hideMinHeightRatio?.let { "%.2f".format(it) }} hold=${hideLongestHoldMs}ms"
    }

    /** Keyed by (material, native). */
    private val measured = HashMap<Pair<Boolean, Boolean>, Curve>()
    private val measuredTranslated = HashMap<Boolean, Sample>()
    private val dialogShown = mutableStateOf(false)
    private val translatedShown = mutableStateOf(false)

    @Composable
    private fun Content() {
        // Enough text under the dialog for the owner window's frame to cost
        // something: a scrim fade re-presents the owner every frame, and a
        // trivial scene would hide a cadence problem a real app shows.
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Color.White)) {
            repeat(HEAVY_ROWS) { row ->
                androidx.compose.material.Text(
                    text = "Row $row - " + "lorem ipsum dolor sit amet ".repeat(HEAVY_REPEATS),
                    color = Color.DarkGray,
                    maxLines = 1,
                )
            }
        }
        val shown by dialogShown
        if (shown) {
            Dialog(onDismissRequest = { }) {
                Box(Modifier.size(DIALOG_W_DP.dp, DIALOG_H_DP.dp).background(DIALOG_COLOR))
            }
        }
    }

    /**
     * The dialog nucleus-demo's Containment gallery opens: a Material 3
     * `AlertDialog` — `Surface` with shape, tonal and shadow elevation, title,
     * body text and two text buttons — under a Material 3 theme. The container
     * is painted [DIALOG_COLOR] so the sampler finds it the same way.
     */
    @Composable
    private fun MaterialContent() {
        androidx.compose.material3.MaterialTheme {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Color.White)) {
                repeat(HEAVY_ROWS) { row ->
                    androidx.compose.material3.Text(
                        text = "Row $row - " + "lorem ipsum dolor sit amet ".repeat(HEAVY_REPEATS),
                        color = Color.DarkGray,
                        maxLines = 1,
                    )
                }
            }
            val shown by dialogShown
            if (shown) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { },
                    containerColor = DIALOG_COLOR,
                    titleContentColor = Color.White,
                    textContentColor = Color.White,
                    title = { androidx.compose.material3.Text("What is a dialog?") },
                    text = {
                        androidx.compose.material3.Text(
                            "A dialog is a type of modal window that appears in front of app content " +
                                "to provide critical information, or prompt for a decision to be made.",
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { }) { androidx.compose.material3.Text("Okay") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { },
                        ) { androidx.compose.material3.Text("Dismiss") }
                    },
                )
            }
        }
    }

    /** A popup whose content is moved by a plain graphicsLayer translation, no animation. */
    @Composable
    private fun TranslatedContent() {
        Box(Modifier.fillMaxSize().background(Color.White))
        val shown by translatedShown
        // Exactly what Dialog.skiko.kt does: a GraphicsLayer created from the
        // *owner window's* GraphicsContext, recorded and drawn inside the layer.
        // Supported across contexts: a skiko RenderNode records a picture and
        // replays it (alpha through saveLayer) on whatever canvas draws it —
        // no GPU resource of the owner's DirectContext is touched inside the
        // popup's. #658's "hang" in the native variant was the case's own
        // screen capture, not this layer.
        val graphicsContext = androidx.compose.ui.platform.LocalGraphicsContext.current
        val layer = androidx.compose.runtime.remember { graphicsContext.createGraphicsLayer() }
        if (shown) {
            androidx.compose.ui.window.Popup(alignment = androidx.compose.ui.Alignment.Center) {
                Box(
                    Modifier
                        .size(DIALOG_W_DP.dp, DIALOG_H_DP.dp)
                        .drawWithContent {
                            layer.record { this@drawWithContent.drawContent() }
                            layer.translationY = STATIC_TRANSLATION_PX
                            layer.scaleX = 0.95f
                            layer.scaleY = 0.95f
                            // Half-transparent like a dialog mid-appearance: alpha
                            // switches the GraphicsLayer to its saveLayer path.
                            layer.alpha = 0.5f
                            drawLayer(layer)
                        }.background(DIALOG_COLOR),
                )
            }
        }
    }

    private fun translated(native: Boolean): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "graphicsLayer translation filmed — ${if (native) "native popup layer" else "in-scene layer"}",
            skip = ::skipReason,
            nativePopupLayers = native,
            content = { TranslatedContent() },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            window.setAlwaysOnTop(true)
            window.focus()
            settle(SETTLE_BEFORE_MILLIS)
            val rect = requireNotNull(bounds()) { "window not mapped" }
            val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
            val region =
                Rectangle(
                    (rect[0] / scale).roundToInt(),
                    (rect[1] / scale).roundToInt(),
                    (rect[2] / scale).roundToInt(),
                    (rect[3] / scale).roundToInt(),
                )
            translatedShown.value = true
            try {
                settle(SETTLE_BEFORE_MILLIS)
                // Off the loop thread, like the film cases' grabber: on Linux a
                // capture from the Tao thread deadlocks on GDK's global lock
                // (#658, see HeadfulRobot) — the case then never returns and
                // the global watchdog takes the whole suite down with it.
                val img =
                    requireNotNull(HeadfulRobot.capture(region)) {
                        "screen capture unavailable: ${HeadfulRobot.unavailableReason}"
                    }
                val s = sample(0, img)
                measuredTranslated[native] = s
                System.err.println(
                    "[dialog-appearance] translated ${if (native) "native" else "in-scene"}: " +
                        "top=${s.dialogTop} bottom=${s.dialogBottom} blueness=${s.blueness}",
                )
                check(s.dialogTop != null) { "the translated popup never showed up on screen" }
            } finally {
                translatedShown.value = false
            }
        }

    private fun compareTranslated(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "graphicsLayer translation — native popup layer lands where the in-scene one does",
            skip = { skipReason() ?: if (measuredTranslated.size < 2) "both filming cases must run first" else null },
            content = { TranslatedContent() },
        ) {
            val a = requireNotNull(measuredTranslated[false])
            val b = requireNotNull(measuredTranslated[true])
            check(
                abs(a.dialogTop!! - b.dialogTop!!) <= SLIDE_TOLERANCE_PX &&
                    abs(a.dialogBottom!! - b.dialogBottom!!) <= SLIDE_TOLERANCE_PX,
            ) {
                "translated content lands elsewhere in a native layer: " +
                    "in-scene top=${a.dialogTop} bottom=${a.dialogBottom} " +
                    "native top=${b.dialogTop} bottom=${b.dialogBottom}"
            }
        }

    private fun film(
        native: Boolean,
        material: Boolean = false,
    ): TaoWindowTestCase =
        TaoWindowTestCase(
            name =
                "${if (material) "Material 3 AlertDialog" else "dialog"} appearance filmed — " +
                    "${if (native) "native popup layer" else "in-scene layer"}",
            skip = ::skipReason,
            nativePopupLayers = native,
            paintDefaultBackground = false,
            content = { if (material) MaterialContent() else Content() },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            // The screen grab sees whatever is on top; the suite's window is not.
            window.setAlwaysOnTop(true)
            window.focus()
            settle(SETTLE_BEFORE_MILLIS)
            val rect = requireNotNull(bounds()) { "window not mapped" }
            val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
            // Robot speaks logical screen points; the window reports physical px.
            val region =
                Rectangle(
                    (rect[0] / scale).roundToInt(),
                    (rect[1] / scale).roundToInt(),
                    (rect[2] / scale).roundToInt(),
                    (rect[3] / scale).roundToInt(),
                )
            val robot = Robot()
            val frames = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, BufferedImage>>())
            val capturing =
                java.util.concurrent.atomic
                    .AtomicBoolean(true)
            // Warm-up: the first composition of a dialog loads fonts and theme
            // tokens; that would be filmed as a slow appearance. It runs BEFORE
            // the grabber starts — the film is a fixed budget of frames, and a
            // host that captures faster than the warm-up lasts would spend the
            // whole budget on it and leave the curve with nothing after
            // `shownNs`, which reads as "the dialog never showed up on screen".
            dialogShown.value = true
            settle(SETTLE_BEFORE_MILLIS)
            dialogShown.value = false
            settle(SETTLE_BEFORE_MILLIS)
            settle(WARMUP_MILLIS)
            // One capture session per half, each with its own frame budget. A
            // single session would spend the whole budget on the appearance —
            // grabbing is much faster than the film lasts — and leave the
            // disappearance with no frames, which reads as "the dialog never
            // went away" in the comparison.
            var grabber: Thread? = null

            // Returns once the grabber is capturing at a steady pace. Its first
            // captures are slow on macOS — on the CI runner the first took
            // ~300 ms, and the one after it ~250 ms more — long enough to miss
            // the whole appearance if the dialog is shown right away: the curve
            // then starts settled, with no animation and no slide-in.
            suspend fun startFilm() {
                capturing.set(true)
                val from = frames.size
                grabber =
                    kotlin.concurrent.thread(name = "dialog-appearance-capture") {
                        while (capturing.get() && frames.size - from < MAX_FRAMES_PER_HALF) {
                            frames += System.nanoTime() to robot.createScreenCapture(region)
                        }
                    }
                // Bounded: a host that never settles to a steady pace is still filmed.
                awaitUntilOrTimeout(STEADY_TIMEOUT_MILLIS) {
                    val recent =
                        synchronized(frames) {
                            if (frames.size - from < STEADY_FRAMES) return@awaitUntilOrTimeout false
                            frames.subList(frames.size - STEADY_FRAMES, frames.size).map { it.first }
                        }
                    recent.zipWithNext().all { (a, b) -> b - a < STEADY_GAP_MILLIS * NANOS_PER_MILLI }
                }
            }

            // Joined off the loop thread: on macOS every capture starts with
            // `LWCToolkit.sync()`, which waits for the AppKit main thread —
            // the Tao loop this driver runs on. A `join()` here parks that
            // thread while the grabber parks on it, and both wait forever
            // (seen on macOS 27: `stopFilm` in `Thread.join`, the grabber in
            // `LWCToolkit.flushNativeSelectors`).
            suspend fun stopFilm() {
                capturing.set(false)
                val running = grabber ?: return
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { running.join() }
                grabber = null
            }
            // A film is only as good as its grabber: a capture that stalls
            // mid-animation (seen on the macOS CI runner: gaps of 150–330 ms
            // between grabs) misses frames the dialog did draw, and reads as a
            // hold, a missing slide-in or a lagging scrim. Such a film is
            // retaken rather than judged; the steadiest attempt is kept.
            var curve: Curve? = null
            var kept: List<Pair<Long, BufferedImage>> = emptyList()
            var keptShownNs = 0L
            for (attempt in 1..MAX_FILM_ATTEMPTS) {
                frames.clear()
                startFilm()
                val shownNs = System.nanoTime()
                dialogShown.value = true
                var hiddenNs = Long.MAX_VALUE
                var hideFilmFrom = Int.MAX_VALUE
                try {
                    settle(FILM_MILLIS)
                    stopFilm()
                    // Filming before the hide, for the same reason as the show. The
                    // frames grabbed while it warms up are dropped below: each is
                    // stamped before its capture runs, so one stamped just before
                    // the hide can already show it, and would be read as the
                    // appearance still changing at its very end.
                    hideFilmFrom = frames.size
                    startFilm()
                    hiddenNs = System.nanoTime()
                    dialogShown.value = false
                    settle(HIDE_FILM_MILLIS)
                } finally {
                    dialogShown.value = false
                    stopFilm()
                }
                settle(SETTLE_BEFORE_MILLIS)
                val take =
                    Curve(
                        frames
                            .filterIndexed { i, (ns, _) -> ns >= shownNs && (i < hideFilmFrom || ns >= hiddenNs) }
                            .map { (ns, img) -> sample((ns - shownNs) / 1_000_000, img) },
                        hideAtMs = (hiddenNs - shownNs) / 1_000_000,
                    )
                val best = curve
                if (best == null || take.maxCaptureGapMs < best.maxCaptureGapMs) {
                    curve = take
                    kept = frames.toList()
                    keptShownNs = shownNs
                }
                if (take.maxCaptureGapMs <= MAX_CAPTURE_GAP_MILLIS) break
                System.err.println(stalledFilmNote(attempt, take, requireNotNull(curve)))
            }
            val film = requireNotNull(curve)
            measured[material to native] = film
            val mode = (if (material) "m3-" else "") + if (native) "native" else "in-scene"
            reportFilm(mode, rect, scale, region, kept, keptShownNs, film)
            System.err.println("[dialog-appearance] $mode: ${film.summary()}")
            System.err.print(film.table())
            check(film.firstVisibleMs != null) { "the dialog never showed up on screen; ${film.summary()}" }
        }

    /** Log line for a film [take] the grabber stalled on, saying what happens next. */
    private fun stalledFilmNote(
        attempt: Int,
        take: Curve,
        steadiest: Curve,
    ): String {
        val next =
            if (attempt < MAX_FILM_ATTEMPTS) {
                "retaking"
            } else {
                "judging the steadiest attempt (${steadiest.maxCaptureGapMs}ms)"
            }
        return "[dialog-appearance] attempt $attempt: the grabber stalled ${take.maxCaptureGapMs}ms " +
            "(limit $MAX_CAPTURE_GAP_MILLIS) while the dialog animated; $next"
    }

    /**
     * Keeps the first and last grabbed frames of the film kept for [mode] on
     * disk (all of them with `-Dnucleus.dialog.appearance.dump=true`) and logs
     * where it was taken from.
     */
    private fun reportFilm(
        mode: String,
        rect: LongArray,
        scale: Float,
        region: Rectangle,
        kept: List<Pair<Long, BufferedImage>>,
        keptShownNs: Long,
        film: Curve,
    ) {
        // Keep the first and last grabbed frames on disk: when a curve reads
        // wrong, the pictures say whether the region or the dialog is off.
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "dialog-appearance").apply { mkdirs() }
        kept.firstOrNull()?.let {
            javax.imageio.ImageIO.write(
                it.second,
                "png",
                java.io.File(dir, "$mode-first.png"),
            )
        }
        kept.lastOrNull()?.let {
            javax.imageio.ImageIO.write(
                it.second,
                "png",
                java.io.File(dir, "$mode-last.png"),
            )
        }
        if (System.getProperty("nucleus.dialog.appearance.dump") == "true") {
            for ((ns, img) in kept) {
                val t = (ns - keptShownNs) / 1_000_000
                if (t in
                    0..DUMP_UNTIL_MS
                ) {
                    javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "$mode-t%03d.png".format(t)))
                }
            }
        }
        val screen =
            java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration
        System.err.println(
            "[dialog-appearance] $mode: window=${rect.toList()} scale=$scale region=$region " +
                "awtScreen=${screen.bounds} awtTransform=${screen.defaultTransform.scaleX} " +
                "frames=${kept.size} captureGap=${film.maxCaptureGapMs}ms dump=$dir",
        )
    }

    private fun compare(material: Boolean = false): TaoWindowTestCase =
        TaoWindowTestCase(
            name =
                "${if (material) "Material 3 AlertDialog" else "dialog"} appearance — " +
                    "native popup layer matches the in-scene layer",
            skip = {
                skipReason()
                    ?: if (measured[material to false] == null || measured[material to true] == null) {
                        "both filming cases must run first"
                    } else {
                        null
                    }
            },
            content = { Content() },
        ) {
            val inScene = requireNotNull(measured[material to false])
            val native = requireNotNull(measured[material to true])
            System.err.println("[dialog-appearance] in-scene: ${inScene.summary()}")
            System.err.println("[dialog-appearance] native:   ${native.summary()}")
            val problems = mutableListOf<String>()

            fun near(
                what: String,
                a: Number?,
                b: Number?,
                tolerance: Number,
            ) {
                if (a == null || b == null) {
                    problems += "$what: in-scene=$a native=$b"
                } else if (abs(a.toDouble() - b.toDouble()) > tolerance.toDouble()) {
                    problems += "$what: in-scene=$a native=$b (tolerance $tolerance)"
                }
            }

            // A time read from grabs is only known to lie between two of them:
            // two such intervals disagree when the gap between them exceeds
            // the tolerance, not when their upper ends do.
            fun nearInTime(
                what: String,
                a: LongRange?,
                b: LongRange?,
                tolerance: Long,
            ) {
                if (a == null || b == null) {
                    problems += "$what: in-scene=$a native=$b"
                } else if (maxOf(0L, a.first - b.last, b.first - a.last) > tolerance) {
                    problems += "$what: in-scene=$a native=$b (tolerance $tolerance)"
                }
            }
            // One-sided: the native layer shows its first frame sooner (its
            // surface presents without waiting for the owner's frame); later
            // than the in-scene layer would be a regression.
            val inSceneFirst = inScene.firstVisibleMs
            val nativeFirst = native.firstVisibleMs
            if (inSceneFirst == null ||
                nativeFirst == null ||
                nativeFirst > inSceneFirst + FIRST_VISIBLE_TOLERANCE_MS
            ) {
                problems +=
                    "first visible (ms): in-scene=$inSceneFirst native=$nativeFirst (tolerance $FIRST_VISIBLE_TOLERANCE_MS)"
            }
            near("appearance duration (ms)", inScene.animationMs, native.animationMs, SETTLE_TOLERANCE_MS)
            near("slide-in (px)", inScene.slideInPx, native.slideInPx, SLIDE_TOLERANCE_PX)
            // Asymmetric, like first visible: the native dialog's surface
            // presents without waiting for the owner frame that draws its
            // scrim, so the scrim trails the content by a frame (measured +14
            // and +18 over in-scene). A ramp *smaller* than in-scene is the
            // regression this guards — a scrim missing or popped in at full
            // strength — and stays strict.
            val rampDelta = native.scrimRamp - inScene.scrimRamp
            if (rampDelta < -COLOR_TOLERANCE || rampDelta > SCRIM_TRAIL_TOLERANCE) {
                problems +=
                    "scrim ramp: in-scene=${inScene.scrimRamp} native=${native.scrimRamp} " +
                    "(tolerance -$COLOR_TOLERANCE/+$SCRIM_TRAIL_TOLERANCE)"
            }
            near("final scrim", inScene.finalScrimRed, native.finalScrimRed, COLOR_TOLERANCE)
            near("final content", inScene.finalBlueness, native.finalBlueness, COLOR_TOLERANCE)
            nearInTime("hide start (ms)", inScene.hideStartWindow, native.hideStartWindow, FIRST_VISIBLE_TOLERANCE_MS)
            nearInTime("hide gone (ms)", inScene.hideGoneWindow, native.hideGoneWindow, SETTLE_TOLERANCE_MS)
            near("hide min height ratio", inScene.hideMinHeightRatio, native.hideMinHeightRatio, HEIGHT_RATIO_TOLERANCE)
            if (native.showLongestHoldMs > maxOf(inScene.showLongestHoldMs + HOLD_TOLERANCE_MS, HOLD_FLOOR_MS)) {
                problems +=
                    "appearance drops frames: longest hold in-scene=${inScene.showLongestHoldMs}ms " +
                    "native=${native.showLongestHoldMs}ms (tolerance $HOLD_TOLERANCE_MS)"
            }
            if (native.hideLongestHoldMs > maxOf(inScene.hideLongestHoldMs + HOLD_TOLERANCE_MS, HOLD_FLOOR_MS)) {
                problems +=
                    "disappearance drops frames: longest hold in-scene=${inScene.hideLongestHoldMs}ms " +
                    "native=${native.hideLongestHoldMs}ms (tolerance $HOLD_TOLERANCE_MS)"
            }
            check(problems.isEmpty()) {
                "the native popup layer's dialog does not appear like the in-scene one:\n  " +
                    problems.joinToString("\n  ")
            }
        }

    /**
     * Reads one grabbed frame; coordinates are logical px inside the window's
     * outer rect.
     *
     * Read where a page of text cannot fool it. The owner window is
     * full of text, and with subpixel (ClearType) rendering its glyphs carry
     * blue and red fringes: a single pixel on the centre column read as the
     * dialog wherever a fringe crossed it (Windows native image: a "dialog"
     * from y=18 that never went away), and a single scrim probe that hit a
     * glyph read it darker than the page. So a row is the dialog only when
     * its colour spans a run around the centre — the dialog is hundreds of px
     * wide, a fringe one or two — and the scrim is the brightest red of a
     * patch, the page between the glyphs.
     */
    private fun sample(
        tMs: Long,
        img: BufferedImage,
    ): Sample {
        val w = img.width
        val h = img.height
        val scrimRed = brightestRed(img, SCRIM_PROBE_INSET, h - SCRIM_PROBE_INSET)
        val x = w / 2
        val run = (-DIALOG_RUN_PX..DIALOG_RUN_PX step DIALOG_RUN_STEP_PX).map { x + it }
        var top: Int? = null
        var bottom: Int? = null
        for (y in FRAME_EDGE_INSET until h - FRAME_EDGE_INSET) {
            if (run.all { isDialogColor(img.getRGB(it, y)) }) {
                if (top == null) top = y
                bottom = y
            }
        }
        val blueness =
            if (top != null && bottom != null) {
                val y = (top + bottom) / 2
                run.map { img.getRGB(it, y).let { c -> blue(c) - red(c) } }.sorted()[run.size / 2]
            } else {
                0
            }
        return Sample(tMs, scrimRed, top, bottom, blueness)
    }

    /** The brightest red channel in the [SCRIM_PATCH_PX] square around ([cx], [cy]). */
    private fun brightestRed(
        img: BufferedImage,
        cx: Int,
        cy: Int,
    ): Int {
        var best = 0
        for (y in cy - SCRIM_PATCH_PX..cy + SCRIM_PATCH_PX) {
            for (x in cx - SCRIM_PATCH_PX..cx + SCRIM_PATCH_PX) {
                if (x in 0 until img.width && y in 0 until img.height) best = maxOf(best, red(img.getRGB(x, y)))
            }
        }
        return best
    }

    /** Anything the dialog's blue could look like while fading in over the scrimmed white. */
    private fun isDialogColor(argb: Int): Boolean = blue(argb) - red(argb) > DIALOG_DETECT_THRESHOLD

    private fun red(argb: Int): Int = (argb shr 16) and 0xFF

    private fun blue(argb: Int): Int = argb and 0xFF

    private fun skipReason(): String? =
        if (java.awt.GraphicsEnvironment.isHeadless()) "no display for Robot capture" else null

    private val DIALOG_COLOR = Color(0xFF1030C0)
    private const val DIALOG_W_DP = 320
    private const val DIALOG_H_DP = 220
    private const val STATIC_TRANSLATION_PX = 40f
    private const val WHITE = 255
    private const val SCRIM_PROBE_INSET = 16
    private const val DIALOG_DETECT_THRESHOLD = 40

    /** Half-width of the run around the centre column a dialog row must span, and its sampling step. */
    private const val DIALOG_RUN_PX = 12
    private const val DIALOG_RUN_STEP_PX = 6

    /** Half-size of the square the scrim is read from. */
    private const val SCRIM_PATCH_PX = 4
    private const val SETTLE_BEFORE_MILLIS = 600L
    private const val WARMUP_MILLIS = 200L
    private const val FILM_MILLIS = 700L
    private const val HIDE_FILM_MILLIS = 500L
    private const val HEAVY_ROWS = 40
    private const val HEAVY_REPEATS = 6

    /**
     * How much longer the native layer may hold one picture during an animation
     * than the in-scene layer did: about two frames at 60 Hz. Across CI and local
     * films the longest hold is one to three frames (11–58 ms) on both layers.
     */
    private const val HOLD_TOLERANCE_MS = 34L

    /**
     * A hold shorter than this is never a dropped-frame failure: the macOS CI
     * runner holds one picture for up to ~76 ms on either layer.
     */
    private const val HOLD_FLOOR_MS = 100L

    /** Recordings per film before the steadiest one is judged anyway. */
    private const val MAX_FILM_ATTEMPTS = 3

    /**
     * The longest gap between grabs a film may have while the dialog animates
     * (~5 frames); longer, and it is retaken.
     */
    private const val MAX_CAPTURE_GAP_MILLIS = 80L

    /** Grabs that must arrive under [STEADY_GAP_MILLIS] apart before a film starts. */
    private const val STEADY_FRAMES = 3
    private const val STEADY_GAP_MILLIS = 50L
    private const val STEADY_TIMEOUT_MILLIS = 3_000L
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val HEIGHT_RATIO_TOLERANCE = 0.15f
    private const val MAX_FRAMES = 200

    /** Per-half budget; the two halves together stay within [MAX_FRAMES]. */
    private const val MAX_FRAMES_PER_HALF = MAX_FRAMES / 2

    private const val DUMP_UNTIL_MS = 1_300L
    private const val SETTLE_PX = 1
    private const val SETTLE_COLOR = 6
    private const val FIRST_VISIBLE_TOLERANCE_MS = 50L
    private const val SETTLE_TOLERANCE_MS = 80L
    private const val SLIDE_TOLERANCE_PX = 4
    private const val COLOR_TOLERANCE = 20

    /** How far the native layer's scrim may trail its content: about two frames of the fade. */
    private const val SCRIM_TRAIL_TOLERANCE = 40

    /**
     * Rows and columns left out of the dialog search at the capture's edges. The
     * capture is the window's outer rect, which on Windows includes the
     * invisible resize borders (~8 px): the desktop shows through there, and the
     * runner's blue wallpaper read as the dialog in every frame.
     */
    private const val FRAME_EDGE_INSET = 16
}
