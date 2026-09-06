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
        val hideStartMs: Long?
            get() {
                val rest = hiding.firstOrNull() ?: return null
                return hiding
                    .firstOrNull {
                        it.dialogTop != rest.dialogTop ||
                            it.blueness != rest.blueness ||
                            it.scrimRed != rest.scrimRed
                    }?.tMs
                    ?.minus(hideAtMs)
            }

        /**
         * The smallest height the dialog's colour spanned while fading out,
         * as a fraction of its resting height. `Dialog.skiko.kt` reports a
         * zero-size `boundsInWindow` during the fade-out; a native surface that
         * followed it shrank the dialog to a square of margin around a point.
         */
        val hideMinHeightRatio: Float?
            get() {
                val rest = visible.lastOrNull() ?: return null
                val restHeight = (rest.dialogBottom!! - rest.dialogTop!!).coerceAtLeast(1)
                val fading = hiding.filter { it.dialogTop != null && it.dialogBottom != null }
                if (fading.isEmpty()) return null
                return fading.minOf { it.dialogBottom!! - it.dialogTop!! }.toFloat() / restHeight
            }

        /** First moment after the hide request where the dialog was gone. */
        val hideGoneMs: Long? get() = hiding.firstOrNull { it.dialogTop == null }?.tMs?.minus(hideAtMs)

        /**
         * Grabs during an animation that show exactly the frame before them.
         * The screen is grabbed faster than the display refreshes, so a few
         * repeats are normal; many more than the in-scene layer shows means
         * frames were dropped.
         */
        fun stalls(phase: List<Sample>): Int =
            phase
                .zipWithNext()
                .count { (a, b) ->
                    a.dialogTop == b.dialogTop &&
                        a.dialogBottom == b.dialogBottom &&
                        a.blueness == b.blueness &&
                        a.scrimRed == b.scrimRed
                }

        val showStalls: Int
            get() {
                val end = settledMs ?: return 0
                return stalls(visible.filter { it.tMs <= end })
            }

        val hideStalls: Int
            get() {
                val start = hideStartMs ?: return 0
                val end = hideGoneMs ?: return 0
                return stalls(hiding.filter { it.tMs - hideAtMs in start..end })
            }
        val firstVisibleMs: Long? get() = visible.firstOrNull()?.tMs
        val finalTop: Int? get() = visible.lastOrNull()?.dialogTop
        val finalBlueness: Int get() = visible.lastOrNull()?.blueness ?: 0
        val finalScrimRed: Int get() = samples.lastOrNull()?.scrimRed ?: WHITE

        /** How far below its resting place the dialog first appeared, in logical px. */
        val slideInPx: Int?
            get() {
                val first = visible.firstOrNull()?.dialogTop ?: return null
                val last = finalTop ?: return null
                return first - last
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

        /** How much darker the scrim got between the dialog's first frame and the end. */
        val scrimRamp: Int
            get() {
                val first = visible.firstOrNull()?.scrimRed ?: return 0
                return first - finalScrimRed
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
                "stalls=$showStalls | hide: start=${hideStartMs}ms gone=${hideGoneMs}ms " +
                "minHeight=${hideMinHeightRatio?.let { "%.2f".format(it) }} stalls=$hideStalls"
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
                val img = Robot().createScreenCapture(region)
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
            val grabber =
                kotlin.concurrent.thread(name = "dialog-appearance-capture") {
                    while (capturing.get() && frames.size < MAX_FRAMES) {
                        frames += System.nanoTime() to robot.createScreenCapture(region)
                    }
                }
            // Warm-up: the first composition of a dialog loads fonts and theme
            // tokens; that would be filmed as a slow appearance.
            dialogShown.value = true
            settle(SETTLE_BEFORE_MILLIS)
            dialogShown.value = false
            settle(SETTLE_BEFORE_MILLIS)
            settle(WARMUP_MILLIS)
            val shownNs = System.nanoTime()
            dialogShown.value = true
            var hiddenNs = Long.MAX_VALUE
            try {
                settle(FILM_MILLIS)
                hiddenNs = System.nanoTime()
                dialogShown.value = false
                settle(HIDE_FILM_MILLIS)
            } finally {
                capturing.set(false)
                grabber.join()
                dialogShown.value = false
            }
            settle(SETTLE_BEFORE_MILLIS)
            val curve =
                Curve(
                    frames
                        .filter { (ns, _) -> ns >= shownNs }
                        .map { (ns, img) -> sample((ns - shownNs) / 1_000_000, img) },
                    hideAtMs = (hiddenNs - shownNs) / 1_000_000,
                )
            measured[material to native] = curve
            val mode = (if (material) "m3-" else "") + if (native) "native" else "in-scene"
            // Keep the first and last grabbed frames on disk: when a curve reads
            // wrong, the pictures say whether the region or the dialog is off.
            val dir = java.io.File(System.getProperty("java.io.tmpdir"), "dialog-appearance").apply { mkdirs() }
            frames.firstOrNull()?.let {
                javax.imageio.ImageIO.write(
                    it.second,
                    "png",
                    java.io.File(dir, "$mode-first.png"),
                )
            }
            frames.lastOrNull()?.let {
                javax.imageio.ImageIO.write(
                    it.second,
                    "png",
                    java.io.File(dir, "$mode-last.png"),
                )
            }
            if (System.getProperty("nucleus.dialog.appearance.dump") == "true") {
                for ((ns, img) in frames) {
                    val t = (ns - shownNs) / 1_000_000
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
                    "frames=${frames.size} dump=$dir",
            )
            System.err.println("[dialog-appearance] $mode: ${curve.summary()}")
            System.err.print(curve.table())
            check(curve.firstVisibleMs != null) { "the dialog never showed up on screen; ${curve.summary()}" }
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
            near("scrim ramp", inScene.scrimRamp, native.scrimRamp, COLOR_TOLERANCE)
            near("final scrim", inScene.finalScrimRed, native.finalScrimRed, COLOR_TOLERANCE)
            near("final content", inScene.finalBlueness, native.finalBlueness, COLOR_TOLERANCE)
            near("hide start (ms)", inScene.hideStartMs, native.hideStartMs, FIRST_VISIBLE_TOLERANCE_MS)
            near("hide gone (ms)", inScene.hideGoneMs, native.hideGoneMs, SETTLE_TOLERANCE_MS)
            near("hide min height ratio", inScene.hideMinHeightRatio, native.hideMinHeightRatio, HEIGHT_RATIO_TOLERANCE)
            if (native.showStalls > inScene.showStalls + STALL_TOLERANCE) {
                problems +=
                    "appearance drops frames: in-scene stalls=${inScene.showStalls} native stalls=${native.showStalls}"
            }
            if (native.hideStalls > inScene.hideStalls + STALL_TOLERANCE) {
                problems +=
                    "disappearance drops frames: in-scene stalls=${inScene.hideStalls} native stalls=${native.hideStalls}"
            }
            check(problems.isEmpty()) {
                "the native popup layer's dialog does not appear like the in-scene one:\n  " +
                    problems.joinToString("\n  ")
            }
        }

    /** Reads one grabbed frame; coordinates are logical px inside the window's outer rect. */
    private fun sample(
        tMs: Long,
        img: BufferedImage,
    ): Sample {
        val w = img.width
        val h = img.height
        val scrim = img.getRGB(SCRIM_PROBE_INSET, h - SCRIM_PROBE_INSET)
        val x = w / 2
        var top: Int? = null
        var bottom: Int? = null
        for (y in 0 until h) {
            if (isDialogColor(img.getRGB(x, y))) {
                if (top == null) top = y
                bottom = y
            }
        }
        val blueness =
            if (top != null && bottom != null) {
                val c = img.getRGB(x, (top + bottom) / 2)
                blue(c) - red(c)
            } else {
                0
            }
        return Sample(tMs, red(scrim), top, bottom, blueness)
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
    private const val SETTLE_BEFORE_MILLIS = 600L
    private const val WARMUP_MILLIS = 200L
    private const val FILM_MILLIS = 700L
    private const val HIDE_FILM_MILLIS = 500L
    private const val HEAVY_ROWS = 40
    private const val HEAVY_REPEATS = 6
    private const val STALL_TOLERANCE = 3
    private const val HEIGHT_RATIO_TOLERANCE = 0.15f
    private const val MAX_FRAMES = 200
    private const val DUMP_UNTIL_MS = 1_300L
    private const val SETTLE_PX = 1
    private const val SETTLE_COLOR = 6
    private const val FIRST_VISIBLE_TOLERANCE_MS = 50L
    private const val SETTLE_TOLERANCE_MS = 80L
    private const val SLIDE_TOLERANCE_PX = 4
    private const val COLOR_TOLERANCE = 20
}
