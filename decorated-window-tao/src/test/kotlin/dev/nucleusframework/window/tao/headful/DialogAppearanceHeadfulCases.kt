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
        val samples: List<Sample>,
    ) {
        val visible: List<Sample> get() = samples.filter { it.dialogTop != null }
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
                appendLine("    t(ms)  scrimR  top  bottom  blueness")
                for (s in samples) {
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
            "firstVisible=${firstVisibleMs}ms settled=${settledMs}ms slideIn=${slideInPx}px " +
                "scrimRamp=$scrimRamp finalScrimRed=$finalScrimRed finalBlueness=$finalBlueness"
    }

    private val measured = HashMap<Boolean, Curve>()
    private val measuredTranslated = HashMap<Boolean, Sample>()
    private val dialogShown = mutableStateOf(false)
    private val translatedShown = mutableStateOf(false)

    @Composable
    private fun Content() {
        Box(Modifier.fillMaxSize().background(Color.White))
        val shown by dialogShown
        if (shown) {
            Dialog(onDismissRequest = { }) {
                Box(Modifier.size(DIALOG_W_DP.dp, DIALOG_H_DP.dp).background(DIALOG_COLOR))
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

    private fun film(native: Boolean): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "dialog appearance filmed — ${if (native) "native popup layer" else "in-scene layer"}",
            skip = ::skipReason,
            nativePopupLayers = native,
            content = { Content() },
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
            settle(WARMUP_MILLIS)
            val shownNs = System.nanoTime()
            dialogShown.value = true
            try {
                settle(FILM_MILLIS)
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
                )
            measured[native] = curve
            val mode = if (native) "native" else "in-scene"
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

    private fun compare(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "dialog appearance — native popup layer matches the in-scene layer",
            skip = { skipReason() ?: if (measured.size < 2) "both filming cases must run first" else null },
            content = { Content() },
        ) {
            val inScene = requireNotNull(measured[false])
            val native = requireNotNull(measured[true])
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
            near("first visible (ms)", inScene.firstVisibleMs, native.firstVisibleMs, FIRST_VISIBLE_TOLERANCE_MS)
            near("settled (ms)", inScene.settledMs, native.settledMs, SETTLE_TOLERANCE_MS)
            near("slide-in (px)", inScene.slideInPx, native.slideInPx, SLIDE_TOLERANCE_PX)
            near("scrim ramp", inScene.scrimRamp, native.scrimRamp, COLOR_TOLERANCE)
            near("final scrim", inScene.finalScrimRed, native.finalScrimRed, COLOR_TOLERANCE)
            near("final content", inScene.finalBlueness, native.finalBlueness, COLOR_TOLERANCE)
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
    private const val MAX_FRAMES = 200
    private const val DUMP_UNTIL_MS = 260L
    private const val SETTLE_PX = 1
    private const val SETTLE_COLOR = 6
    private const val FIRST_VISIBLE_TOLERANCE_MS = 50L
    private const val SETTLE_TOLERANCE_MS = 80L
    private const val SLIDE_TOLERANCE_PX = 4
    private const val COLOR_TOLERANCE = 20
}
