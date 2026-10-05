package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.unit.IntRect
import org.jetbrains.skia.Rect

/**
 * Partial redraw (#755): repaint and present only what changed.
 *
 * A frame's *damage* — the window pixels that differ from the previous frame
 * — comes from [LayerDamageTracker]. The back buffer the frame renders into
 * is not the previous frame, though: it is the one presented
 * *buffer age* swaps ago (`EGL_EXT_buffer_age`), so the frame repaints the
 * union of its own damage and that of every frame presented since, which is
 * what [DamageHistory] keeps. The compositor is then told the frame's own
 * damage (`eglSwapBuffersWithDamage` → `wl_surface.damage_buffer`).
 *
 * Windows (ANGLE / D3D11): the window surface is ANGLE's offscreen texture,
 * kept with `EGL_BUFFER_PRESERVED`, so its age is always 1; the frame is
 * presented with `eglPostSubBufferNV`, which copies only the damaged
 * rectangle into the swap chain and hands it to DXGI as the dirty rect.
 *
 * Any doubt is a full repaint: damage unknown, buffer age 0 or older than the
 * history, a resize, a change of clear colour or frame decoration.
 *
 * Opt-in: `nucleusOptimization { partialRedraw = true }` in the Nucleus
 * plugin patches Compose (see [LayerDamageTracker]) and turns it on with
 * `-Dnucleus.tao.partialRedraw=true`, also baked into
 * `nucleus/nucleus-app.properties` (`optimization.partialRedraw`) for native
 * images, which have no launcher `.cfg`.
 *
 * Switches (system properties):
 *  - `nucleus.tao.partialRedraw=true|false` forces it on or off, whatever the
 *    app properties say (environment variable `NUCLEUS_TAO_PARTIAL_REDRAW=0`
 *    turns off the native side too);
 *  - `nucleus.tao.partialRedraw.debug=true` logs why frames repaint in full,
 *    whenever that changes;
 *  - `nucleus.tao.partialRedraw.tint=true` flashes what changed in red,
 *    every other frame, over frames repainted in full — Android's "show GPU
 *    view updates" (see [drawDebugTint]);
 *  - `nucleus.tao.partialRedraw.verify=true` re-renders every partial frame in
 *    full off screen and compares the two pixel for pixel, logging any
 *    difference — the oracle the end-to-end checks run against. Slow.
 */
internal object PartialRedraw {
    /**
     * On with `nucleusOptimization { partialRedraw }`: the system property
     * (the launcher `.cfg`, `run`) wins, else the app-properties key (native
     * images). Off by default.
     */
    val enabled: Boolean =
        System.getProperty(PROPERTY)?.let { it == "true" } ?: readAppPropertiesFlag()

    @Suppress("TooGenericExceptionCaught")
    private fun readAppPropertiesFlag(): Boolean =
        try {
            PartialRedraw::class.java.classLoader
                ?.getResourceAsStream(APP_PROPERTIES)
                ?.use { java.util.Properties().apply { load(it) } }
                ?.getProperty(APP_PROPERTIES_KEY) == "true"
        } catch (_: Exception) {
            false
        }

    /** `-Dnucleus.tao.partialRedraw.debug=true`: log why frames repaint in full. */
    val debug: Boolean = System.getProperty("nucleus.tao.partialRedraw.debug") == "true"

    /** `-Dnucleus.tao.partialRedraw.verify=true`: compare every partial frame with a full render. */
    val verify: Boolean = System.getProperty("nucleus.tao.partialRedraw.verify") == "true"

    /**
     * `-Dnucleus.tao.partialRedraw.tint=true`: flash what each frame changed
     * (see [drawDebugTint]) — unless [verify]ing, which compares partial frames
     * this mode no longer draws.
     */
    val debugTint: Boolean = System.getProperty("nucleus.tao.partialRedraw.tint") == "true" && !verify

    /** Keep in sync with the plugin's `NUCLEUS_PARTIAL_REDRAW_PROPERTY`. */
    private const val PROPERTY = "nucleus.tao.partialRedraw"
    private const val APP_PROPERTIES = "nucleus/nucleus-app.properties"

    /** Keep in sync with the plugin's `NUCLEUS_PARTIAL_REDRAW_RESOURCE_KEY`. */
    private const val APP_PROPERTIES_KEY = "optimization.partialRedraw"

    /** HWUI's flash colour (`FrameInfoVisualizer`): translucent red. */
    @Suppress("MagicNumber")
    private const val DEBUG_TINT: Int = 0x7FFF0000

    /**
     * [debugTint], as Android's HWUI draws "show GPU view updates"
     * (`FrameInfoVisualizer::draw`): every other drawn frame, frame number
     * [frame] flashes what it changed, [damage] — `null` for the whole
     * [width]×[height] frame — in translucent red. Hosts repaint every frame in
     * full meanwhile ([debugRepaint]), as HWUI does (`unionDirty` empties the
     * dirty rectangle), so a flash lasts one frame instead of staying in a
     * preserved buffer.
     */
    fun drawDebugTint(
        canvas: org.jetbrains.skia.Canvas,
        damage: IntRect?,
        width: Int,
        height: Int,
        frame: Int,
    ) {
        if (frame and 1 != 0) return
        org.jetbrains.skia.Paint().use { paint ->
            paint.color = DEBUG_TINT
            val area = damage?.toSkiaRect() ?: Rect.makeWH(width.toFloat(), height.toFloat())
            canvas.drawRect(area, paint)
        }
    }

    /**
     * What a frame repaints, given what partial redraw would repaint: all of it
     * (`null`) under [debugTint], unless nothing changed — HWUI still skips
     * empty frames.
     */
    fun debugRepaint(repaint: IntRect?): IntRect? = if (debugTint && repaint?.isEmpty != true) null else repaint

    /**
     * A repaint covering this much of the frame is done in full: a partial
     * frame has a fixed cost (clip, scissored clears, a sub-rectangle
     * present), so past some coverage it costs more than repainting
     * everything. Measured with the real scene recording, full vs partial,
     * GPU-synced medians:
     *  - macOS, Apple Silicon, 2560×1050: ~0.3 ms for a few pixels against
     *    ~0.7 ms in full; breaks even around 80 %, up to ~5 % more above,
     *    still 6–26 % saved at 70 %;
     *  - Windows, ANGLE / D3D11, 2560×1032, frames interleaved: ~0.4 ms
     *    against ~0.9 ms; breaks even at 80 % (1.01–1.05), up to ~9 % more
     *    above, still 2–11 % saved at 70 %.
     */
    const val FULL_REPAINT_PERCENT: Long = 80L

    /**
     * EGL damage rectangles for [rect] on a [surfaceHeight]-tall buffer:
     * `(x, y, w, h)` with a bottom-left origin.
     */
    fun eglRects(
        rect: IntRect,
        surfaceHeight: Int,
    ): IntArray = intArrayOf(rect.left, surfaceHeight - rect.bottom, rect.width, rect.height)
}

/**
 * The damage of the last frames presented to one surface, newest first, for
 * buffer-age partial redraw — see [PartialRedraw].
 */
internal class DamageHistory {
    /** Newest first; `null` marks a frame whose damage was not known (a full frame). */
    private val frames = arrayOfNulls<IntRect>(CAPACITY)
    private var size = 0

    /**
     * The region a frame whose own damage is [frameDamage] must repaint into a
     * back buffer of age [bufferAge], or `null` for everything.
     */
    fun repaintRegion(
        frameDamage: IntRect?,
        bufferAge: Int,
    ): IntRect? {
        if (frameDamage == null || bufferAge <= 0 || bufferAge - 1 > size) return null
        var region: IntRect = frameDamage
        for (i in 0 until bufferAge - 1) {
            val earlier = frames[i] ?: return null
            region = region.union(earlier)
        }
        return region
    }

    /** Records the frame just presented; `null` for a frame of unknown damage. */
    fun push(frameDamage: IntRect?) {
        for (i in CAPACITY - 1 downTo 1) frames[i] = frames[i - 1]
        frames[0] = frameDamage
        if (size < CAPACITY) size++
    }

    /** Forgets every frame: the next one is full whatever its buffer's age. */
    fun clear() {
        frames.fill(null)
        size = 0
    }

    private companion object {
        /** Deepest buffer age honoured; older buffers (or a longer swap chain) repaint in full. */
        const val CAPACITY = 4
    }
}

/** At least [PartialRedraw.FULL_REPAINT_PERCENT] of a [width] × [height] frame: cheaper repainted in full. */
@Suppress("MagicNumber")
internal fun IntRect.coversMostOf(
    width: Int,
    height: Int,
): Boolean = this.width.toLong() * this.height * 100 >= PartialRedraw.FULL_REPAINT_PERCENT * width.toLong() * height

/** Bounding box of two rectangles, an empty one leaving the other unchanged. */
internal fun IntRect.union(other: IntRect): IntRect =
    when {
        other.width <= 0 || other.height <= 0 -> this
        width <= 0 || height <= 0 -> other
        else ->
            IntRect(
                minOf(left, other.left),
                minOf(top, other.top),
                maxOf(right, other.right),
                maxOf(bottom, other.bottom),
            )
    }

/**
 * A frame that changed nothing still presents — the swap is what paces the
 * frame loop — so its damage becomes one pixel. Any pixel will do: everything
 * outside a frame's repaint region already holds that frame's content.
 */
internal fun IntRect.orAPixel(): IntRect = if (width <= 0 || height <= 0) IntRect(0, 0, 1, 1) else this

/**
 * A frame whose damage is empty changed nothing on screen: it is neither drawn
 * nor presented (Linux and Windows hosts). Never while verifying — the oracle
 * compares what a frame draws.
 */
internal fun IntRect.isIdle(): Boolean = (width <= 0 || height <= 0) && !PartialRedraw.verify

internal fun IntRect.toSkiaRect(): org.jetbrains.skia.Rect =
    org.jetbrains.skia.Rect
        .makeLTRB(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

/**
 * The `nucleus.tao.partialRedraw.verify` oracle: compares each frame with the
 * same frame rendered again, in full, off screen. Partial frames are the test;
 * full frames are the control — a difference there is the scene drawing
 * differently twice in a row, not damage the tracker missed. The summary line
 * is what the end-to-end checks parse.
 */
internal object PartialRedrawVerifier {
    private val logger =
        java.util.logging.Logger
            .getLogger(PartialRedrawVerifier::class.java.name)

    /** Partial frames compared so far. */
    @Volatile
    var framesChecked: Int = 0
        private set

    /**
     * Partial frames that differ from their full render where it matters:
     * outside the repainted region — pixels a missed damage left stale — or
     * by more than [NOISE_DELTA] inside it.
     */
    @Volatile
    var framesMismatched: Int = 0
        private set

    /** Full frames compared so far (the control). */
    @Volatile
    var framesFull: Int = 0
        private set

    /** Full frames that differed from their second render (the control). */
    @Volatile
    var framesFullMismatched: Int = 0
        private set

    /** One line for the end-to-end checks to parse. */
    fun summary(): String =
        "Partial redraw verify: checked=$framesChecked mismatched=$framesMismatched " +
            "full=$framesFull fullMismatched=$framesFullMismatched"

    /**
     * Compares [frame] with [reference] (same size, N32 premul). [repaint] is
     * the region the frame repainted, `null` for a full frame.
     */
    fun compare(
        frame: org.jetbrains.skia.Bitmap,
        reference: org.jetbrains.skia.Bitmap,
        repaint: IntRect?,
    ) {
        val a = frame.readPixels() ?: return
        val b = reference.readPixels() ?: return
        if (repaint != null) framesChecked++ else framesFull++
        val diff = PixelDiff(a, b, frame.width, frame.height, repaint)
        val count = diff.count
        val maxDelta = diff.maxDelta
        val outside = diff.outside
        val left = diff.left
        val top = diff.top
        val right = diff.right
        val bottom = diff.bottom
        // GPU rasterisation is not bit-exact from one pass to the next — the
        // full-frame control shows the same ±1-2 noise, inside the repaint and
        // in the pixels kept from earlier frames alike. A missed damage leaves
        // stale pixels outside the repaint at full contrast: only differences
        // above the noise count.
        val failed = outside > 0 || maxDelta > NOISE_DELTA
        if (count > 0 && (failed || repaint == null)) {
            if (repaint != null) framesMismatched++ else framesFullMismatched++
            if (failed) dump(frame, reference)
            logger.warning(
                "Partial redraw ${if (repaint != null) "mismatch" else "control mismatch"}: $count px differ " +
                    "(max delta $maxDelta, $outside outside the repaint) " +
                    "in [$left,$top - ${right + 1},${bottom + 1}] " +
                    "(repainted $repaint)",
            )
        }
        if ((framesChecked + framesFull) % REPORT_EVERY == 0) logger.info(summary())
    }

    private var dumps = 0

    /** Per-pixel difference of two N32 frames, against the region [repaint] (`null` = everything). */
    private class PixelDiff(
        a: ByteArray,
        b: ByteArray,
        width: Int,
        height: Int,
        repaint: IntRect?,
    ) {
        var count = 0
        var maxDelta = 0

        /** Pixels outside the repaint that differ by more than the noise. */
        var outside = 0
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE

        init {
            val rowBytes = a.size / height
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val delta = pixelDelta(a, b, y * rowBytes + x * BYTES_PER_PIXEL)
                    if (delta > 0) add(x, y, delta, repaint)
                }
            }
        }

        private fun add(
            x: Int,
            y: Int,
            delta: Int,
            repaint: IntRect?,
        ) {
            count++
            maxDelta = maxOf(maxDelta, delta)
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
            if (delta > NOISE_DELTA && repaint != null && !repaint.containsPixel(x, y)) outside++
        }

        private fun pixelDelta(
            a: ByteArray,
            b: ByteArray,
            i: Int,
        ): Int {
            var delta = 0
            for (c in 0 until BYTES_PER_PIXEL) {
                val d = (a[i + c].toInt() and BYTE_MASK) - (b[i + c].toInt() and BYTE_MASK)
                delta = maxOf(delta, kotlin.math.abs(d))
            }
            return delta
        }

        private fun IntRect.containsPixel(
            x: Int,
            y: Int,
        ): Boolean = x >= left && x < right && y >= top && y < bottom
    }

    /** `-Dnucleus.tao.partialRedraw.verify.dump=<dir>`: writes the first failing pairs as PNG. */
    private fun dump(
        frame: org.jetbrains.skia.Bitmap,
        reference: org.jetbrains.skia.Bitmap,
    ) {
        val dir = System.getProperty("nucleus.tao.partialRedraw.verify.dump") ?: return
        if (dumps >= MAX_DUMPS) return
        val index = dumps++
        for ((name, bitmap) in listOf("partial" to frame, "full" to reference)) {
            val data =
                org.jetbrains.skia.Image
                    .makeFromBitmap(bitmap)
                    .encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG) ?: continue
            java.io.File(dir, "mismatch-$index-$name.png").writeBytes(data.bytes)
        }
    }

    /** Logs [summary] every so many frames — JUL drops what is logged from a shutdown hook. */
    private const val REPORT_EVERY = 60

    /** Largest per-channel difference two renders of the same frame show (rasterisation noise). */
    private const val NOISE_DELTA = 2

    private const val MAX_DUMPS = 3

    private const val BYTES_PER_PIXEL = 4

    private const val BYTE_MASK = 0xFF
}

/**
 * `nucleus.tao.partialRedraw.debug`: how much of the window frames repaint,
 * and which layers make them, summed over a few seconds.
 */
@Suppress("MagicNumber") // percentages and the [frames, pixels] pair
internal class PartialRedrawStats {
    private var frames = 0
    private var partialFrames = 0
    private var repaintedPixels = 0L
    private var windowPixels = 0L
    private var lastReportNs = System.nanoTime()
    private val sources = HashMap<String, LongArray>()
    private val bufferAges = java.util.TreeMap<Int, Int>()

    /** Counts a frame; returns the report line when one is due. */
    fun frame(
        repaint: IntRect?,
        windowArea: Int,
        frameSources: List<Pair<IntRect, String>>,
        bufferAge: Int,
    ): String? {
        frames++
        bufferAges.merge(bufferAge, 1, Int::plus)
        windowPixels += windowArea
        repaintedPixels += repaint?.let { it.width.toLong() * it.height } ?: windowArea.toLong()
        if (repaint != null) partialFrames++
        for ((area, source) in frameSources) {
            val entry = sources.getOrPut(source) { LongArray(2) }
            entry[0]++
            entry[1] += area.width.toLong() * area.height
        }
        val now = System.nanoTime()
        if (now - lastReportNs < REPORT_NS) return null
        val seconds = (now - lastReportNs) / 1e9
        val top =
            sources.entries
                .sortedByDescending { it.value[1] }
                .take(TOP_SOURCES)
                .joinToString("\n") { (source, v) -> "    ${v[0]} frames, ${v[1] / v[0]} px avg — $source" }
        val line =
            "Partial redraw: %.0f fps, %d%% partial, %.1f%% of the window repainted on average, buffer ages %s\n%s"
                .format(
                    frames / seconds,
                    partialFrames * 100 / frames.coerceAtLeast(1),
                    repaintedPixels * 100.0 / windowPixels.coerceAtLeast(1),
                    bufferAges,
                    top,
                )
        frames = 0
        partialFrames = 0
        repaintedPixels = 0
        windowPixels = 0
        sources.clear()
        bufferAges.clear()
        lastReportNs = now
        return line
    }

    private companion object {
        const val REPORT_NS = 3_000_000_000L
        const val TOP_SOURCES = 6
    }
}
