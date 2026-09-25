package screencapturedemo

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.screencapture.CaptureDisplay
import dev.nucleusframework.screencapture.CaptureFailure
import dev.nucleusframework.screencapture.CaptureRegion
import dev.nucleusframework.screencapture.ScreenCapture
import dev.nucleusframework.screencapture.ScreenCaptureException
import dev.nucleusframework.screencapture.ScreenImage
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.TaoWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * The E2E checks. Each one logs `PASS` / `FAIL` lines to stdout and to [logFile]; the process
 * exits with the number of failures.
 */
internal class SelfTest(
    private val window: TaoWindow,
    private val windowId: Long,
    private val showCover: suspend (Boolean) -> Unit,
    private val setMinimized: suspend (Boolean) -> Unit,
) {
    private val logFile =
        File(
            System.getenv("SCREEN_CAPTURE_DEMO_LOG")
                ?: File(System.getProperty("java.io.tmpdir"), "screen-capture-demo.log").path,
        )
    private var failures = 0
    private val tortureIterations = System.getenv("SCREEN_CAPTURE_DEMO_TORTURE")?.toIntOrNull() ?: 800

    fun log(line: String) {
        val text = "${LocalTime.now()} $line"
        println(text)
        runCatching { logFile.appendText("$text\n") }
    }

    private fun check(
        name: String,
        condition: Boolean,
        detail: () -> String = { "" },
    ) {
        if (condition) {
            log("PASS $name ${detail()}")
        } else {
            failures++
            log("FAIL $name ${detail()}")
        }
    }

    private inline fun step(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Throwable) {
            failures++
            log("FAIL $name threw ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }

    suspend fun run(): Int {
        logFile.delete()
        log(
            "START pid=${ProcessHandle.current().pid()} backend=${ScreenCapture.backend} os=${Platform.Current} windowId=$windowId",
        )
        delay(1500) // first frames, compositor settle
        val displays = ScreenCapture.displays()
        displays.forEach { log("DISPLAY $it") }

        step("displays match TaoMonitors") { checkDisplays(displays) }
        step("window capture") { checkWindow() }
        step("display capture") { checkDisplayCapture(displays) }
        step("own UI thread") { checkOnUiThread() }
        step("blocked UI thread") { checkBlockedUiThread() }
        step("occluded window") { checkOccluded(displays) }
        step("minimized window") { checkMinimized() }
        step("cursor") { checkCursor(displays) }
        step("hung foreign window") { checkHungWindow() }
        step("errors") { checkErrors(displays) }
        log("PHASE torture-begin")
        delay(2000)
        step("torture") { torture(displays) }
        System.gc()
        log("PHASE torture-end heapMb=${usedHeapMb()}")
        delay(3000)
        log("DONE failures=$failures")
        return failures
    }

    private fun checkDisplays(displays: List<CaptureDisplay>) {
        if (Platform.Current != Platform.Windows) return
        val monitors = TaoMonitors.all(window)
        check("display count", displays.size == monitors.size) { "${displays.size} vs ${monitors.size}" }
        for (m in monitors) {
            val d = displays.firstOrNull { it.id.equals(m.id, ignoreCase = true) }
            check("display ${m.id} known", d != null)
            if (d == null) continue
            val b = d.bounds!!
            check(
                "display ${m.id} bounds",
                b.x == m.boundsPx.left &&
                    b.y == m.boundsPx.top &&
                    b.width == m.boundsPx.width &&
                    b.height == m.boundsPx.height,
            ) { "$b vs ${m.boundsPx}" }
            check("display ${m.id} scale", kotlin.math.abs(d.scaleFactor - m.scaleFactor) < 0.01f) {
                "${d.scaleFactor} vs ${m.scaleFactor}"
            }
            check("display ${m.id} primary", d.isPrimary == m.isPrimary)
        }
    }

    private fun expectPattern(
        name: String,
        image: ScreenImage,
    ): Pair<Int, Int>? {
        val found = TargetPattern.find(image)
        if (found.size != 1) saveSample("fail-" + name.replace(' ', '-'), image)
        check("$name finds one pattern", found.size == 1) { "found=$found in $image" }
        val origin = found.singleOrNull() ?: return null
        val (count, samples) = TargetPattern.mismatches(image, origin.first, origin.second)
        check("$name pixel-exact", count == 0) { "mismatches=$count $samples at $origin" }
        return origin
    }

    private fun checkWindow() {
        val image: ScreenImage
        val ms = measureTimeMillis { image = ScreenCapture.captureWindow(windowId) }
        log("INFO window capture ${image.width}x${image.height} in ${ms}ms")
        expectPattern("window", image)
        saveSample("window", image)
    }

    private fun displayUnderWindow(displays: List<CaptureDisplay>): CaptureDisplay =
        TaoMonitors.forWindow(window)?.let { m -> displays.firstOrNull { it.id.equals(m.id, ignoreCase = true) } }
            ?: displays.first()

    private fun checkDisplayCapture(displays: List<CaptureDisplay>) {
        val display = displayUnderWindow(displays)
        val times = LongArray(10)
        var full: ScreenImage? = null
        for (i in times.indices) times[i] = measureTimeMillis { full = ScreenCapture.captureDisplay(display) }
        val image = full!!
        log(
            "INFO display ${display.id} ${image.width}x${image.height} median=${times.sorted()[5]}ms max=${times.max()}ms",
        )
        check(
            "display size",
            image.width == display.widthPx && image.height == display.heightPx,
        ) { "$image vs $display" }
        saveSample("display", image)
        val origin = expectPattern("display", image) ?: return

        // A region exactly over the pattern must be the pattern, at the region's origin.
        val region = CaptureRegion(origin.first, origin.second, TargetPattern.WIDTH, TargetPattern.HEIGHT)
        val part = ScreenCapture.captureDisplay(display, region)
        check("region size", part.width == region.width && part.height == region.height) { "$part" }
        check("region at origin", TargetPattern.find(part) == listOf(0 to 0)) { "${TargetPattern.find(part)}" }
        check("region pixel-exact", TargetPattern.mismatches(part, 0, 0).first == 0) {
            "${TargetPattern.mismatches(part, 0, 0)}"
        }

        // Regions shifted by every sub-offset still line up to the pixel.
        var misaligned = 0
        for (dx in -3..3) {
            for (dy in -3..3) {
                val shifted =
                    CaptureRegion(origin.first + dx, origin.second + dy, TargetPattern.WIDTH, TargetPattern.HEIGHT)
                val shot = ScreenCapture.captureDisplay(display, shifted)
                val expected = (-dx) to (-dy)
                if (dx > 0 || dy > 0) continue // the marker is cut; checked through the full image below
                if (TargetPattern.find(shot) != listOf(expected)) misaligned++
            }
        }
        check("shifted regions aligned", misaligned == 0) { "misaligned=$misaligned" }
    }

    private suspend fun checkOnUiThread() {
        val image = withContext(Dispatchers.Main) { ScreenCapture.captureWindow(windowId) }
        expectPattern("window from its own UI thread", image)
        val shot = withContext(Dispatchers.Main) { ScreenCapture.captureDisplay(ScreenCapture.primaryDisplay()!!) }
        check("display from UI thread", shot.width > 0)
    }

    /** The UI thread waits on the capturing thread: PrintWindow's WM_PRINT can never be answered. */
    private suspend fun checkBlockedUiThread() {
        var image: ScreenImage? = null
        val ms =
            withContext(Dispatchers.Main) {
                measureTimeMillis {
                    image = runBlocking(Dispatchers.IO) { ScreenCapture.captureWindow(windowId) }
                }
            }
        check("blocked UI thread returns", ms < 5000) { "${ms}ms" }
        image?.let { expectPattern("window while its UI thread is blocked", it) }
        // The abandoned PrintWindow completes once the UI thread pumps again; the next capture is normal.
        delay(300)
        val next: ScreenImage
        val nextMs = measureTimeMillis { next = ScreenCapture.captureWindow(windowId) }
        check("capture after the blocked one is fast", nextMs < 1000) { "${nextMs}ms" }
        expectPattern("window after the blocked one", next)
    }

    private suspend fun checkOccluded(displays: List<CaptureDisplay>) {
        showCover(true)
        delay(1200)
        try {
            val display = displayUnderWindow(displays)
            val screen = ScreenCapture.captureDisplay(display)
            check(
                "cover hides the pattern on screen",
                TargetPattern.find(screen).isEmpty(),
            ) { "${TargetPattern.find(screen)}" }
            val image = ScreenCapture.captureWindow(windowId)
            if (Platform.Current == Platform.Windows) {
                expectPattern("occluded window", image)
            } else {
                log("INFO occluded window found=${TargetPattern.find(image)}")
            }
            saveSample("occluded", image)
        } finally {
            showCover(false)
            delay(800)
        }
    }

    private suspend fun checkMinimized() {
        setMinimized(true)
        delay(1000)
        try {
            val error = runCatching { ScreenCapture.captureWindow(windowId) }.exceptionOrNull()
            check(
                "minimized window is not capturable",
                (error as? ScreenCaptureException)?.failure == CaptureFailure.WindowNotFound,
            ) {
                "$error"
            }
        } finally {
            setMinimized(false)
            delay(1200)
        }
        expectPattern("restored window", ScreenCapture.captureWindow(windowId))
    }

    /**
     * Wherever the cursor is: of three captures (without, with, without) the cursor is what only
     * the middle one has � pixels the two others agree on. Animated content can do that once, so
     * only pixels doing it in every one of three trials count. They must be there and cursor-sized.
     */
    private fun checkCursor(displays: List<CaptureDisplay>) {
        var bbox: IntArray? = null
        for (display in displays) {
            var mask: BooleanArray? = null
            repeat(3) {
                val before = ScreenCapture.captureDisplay(display, includeCursor = false).toArgbArray()
                val with = ScreenCapture.captureDisplay(display, includeCursor = true).toArgbArray()
                val after = ScreenCapture.captureDisplay(display, includeCursor = false).toArgbArray()
                val trial = BooleanArray(with.size) { i -> before[i] == after[i] && with[i] != before[i] }
                mask = mask?.let { m -> BooleanArray(m.size) { i -> m[i] && trial[i] } } ?: trial
            }
            val box = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1)
            mask!!.forEachIndexed { i, set ->
                if (set) {
                    val x = i % display.widthPx
                    val y = i / display.widthPx
                    box[0] = minOf(box[0], x)
                    box[1] = minOf(box[1], y)
                    box[2] = maxOf(box[2], x)
                    box[3] = maxOf(box[3], y)
                }
            }
            if (box[2] >= 0) {
                check("cursor on one display only", bbox == null)
                bbox = box
                log("INFO cursor on ${display.id} bbox=(${box[0]},${box[1]})-(${box[2]},${box[3]})")
            }
        }
        val box = bbox
        if (System.getenv("SCREEN_CAPTURE_DEMO_CURSOR_EXPECTED") == "1") check("cursor drawn", box != null)
        if (box != null) check("cursor sized", box[2] - box[0] < 128 && box[3] - box[1] < 128) { box.joinToString() }
    }

    private fun checkHungWindow() {
        val hung =
            System.getenv("SCREEN_CAPTURE_DEMO_HUNG_HWND")?.toLongOrNull()
                ?: return log("SKIP hung window: not provided")
        repeat(3) { attempt ->
            var outcome = ""
            val ms =
                measureTimeMillis {
                    outcome =
                        try {
                            ScreenCapture.captureWindow(hung).toString()
                        } catch (e: ScreenCaptureException) {
                            e.failure.name
                        }
                }
            check("hung window capture #$attempt returns in time", ms < 4000) { "${ms}ms $outcome" }
        }
    }

    private fun checkErrors(displays: List<CaptureDisplay>) {
        val display = displays.first()

        fun failureOf(block: () -> Unit) = (runCatching(block).exceptionOrNull() as? ScreenCaptureException)?.failure
        check(
            "region outside",
            failureOf { ScreenCapture.captureDisplay(display, CaptureRegion(-500, -500, 10, 10)) } ==
                CaptureFailure.InvalidRegion,
        )
        check("window 0", failureOf { ScreenCapture.captureWindow(0) } == CaptureFailure.WindowNotFound)
    }

    private fun torture(displays: List<CaptureDisplay>) {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads + 3)
        val errors = ConcurrentLinkedQueue<String>()
        val captures = AtomicInteger()
        val invalid = AtomicInteger()
        val pixels =
            java.util.concurrent.atomic
                .AtomicLong()
        val start = System.nanoTime()
        repeat(threads) { worker ->
            pool.execute {
                val random = Random(worker * 7919)
                repeat(tortureIterations) {
                    val display = displays[random.nextInt(displays.size)]
                    val region =
                        when (random.nextInt(6)) {
                            0 -> null
                            1 ->
                                CaptureRegion(
                                    random.nextInt(-5000, 5000),
                                    random.nextInt(-5000, 5000),
                                    random.nextInt(1, 10_000),
                                    random.nextInt(1, 10_000),
                                )
                            2 ->
                                CaptureRegion(
                                    random.nextInt(Int.MIN_VALUE, Int.MAX_VALUE),
                                    random.nextInt(Int.MIN_VALUE, Int.MAX_VALUE),
                                    random.nextInt(1, Int.MAX_VALUE),
                                    random.nextInt(1, Int.MAX_VALUE),
                                )
                            3 -> CaptureRegion(display.widthPx - 1, display.heightPx - 1, 1, 1)
                            else ->
                                CaptureRegion(
                                    random.nextInt(0, display.widthPx),
                                    random.nextInt(0, display.heightPx),
                                    random.nextInt(1, 300),
                                    random.nextInt(1, 300),
                                )
                        }
                    try {
                        val image = ScreenCapture.captureDisplay(display, region, includeCursor = random.nextBoolean())
                        if (region != null &&
                            (image.width > region.width || image.height > region.height)
                        ) {
                            errors += "$region -> $image"
                        }
                        pixels.addAndGet(image.width.toLong() * image.height)
                        captures.incrementAndGet()
                    } catch (e: ScreenCaptureException) {
                        if (e.failure ==
                            CaptureFailure.InvalidRegion
                        ) {
                            invalid.incrementAndGet()
                        } else {
                            errors +=
                                "${e.failure} ${e.message}"
                        }
                    } catch (e: Throwable) {
                        errors += "${e::class.simpleName} ${e.message}"
                    }
                }
            }
        }
        // Window captures of our own window, concurrently with the display ones.
        repeat(2) { worker ->
            pool.execute {
                repeat(tortureIterations / 4) {
                    try {
                        val image = ScreenCapture.captureWindow(windowId, includeCursor = it % 2 == 0)
                        val found = TargetPattern.find(image)
                        if (found.size != 1) {
                            if (errors.isEmpty()) saveSample("fail-torture-window", image)
                            errors += "window capture found $found"
                        }
                        captures.incrementAndGet()
                    } catch (e: Throwable) {
                        errors += "window ${e::class.simpleName} ${e.message}"
                    }
                }
            }
        }
        // Garbage ids and display enumeration.
        pool.execute {
            val random = Random(42)
            repeat(tortureIterations / 2) {
                runCatching { ScreenCapture.captureWindow(random.nextLong() or 0x7000_0000_0000_0000L) }
                if (ScreenCapture.displays().size != displays.size) errors += "display count changed"
            }
        }
        pool.shutdown()
        val finished = pool.awaitTermination(10, TimeUnit.MINUTES)
        val seconds = (System.nanoTime() - start) / 1e9
        check("torture finished", finished)
        check("torture errors", errors.isEmpty()) { "${errors.size} ${errors.take(5)}" }
        log(
            "INFO torture captures=${captures.get()} invalid=${invalid.get()} megapixels=${pixels.get() / 1_000_000} " +
                "seconds=${"%.1f".format(seconds)}",
        )
    }

    private fun saveSample(
        name: String,
        image: ScreenImage,
    ) {
        val dir = System.getenv("SCREEN_CAPTURE_DEMO_OUT") ?: return
        File(dir, "$name.png").apply { parentFile.mkdirs() }.writeBytes(image.toPng())
    }

    private fun usedHeapMb(): Long = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1024 * 1024) }
}
