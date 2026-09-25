package dev.nucleusframework.screencapture

import dev.nucleusframework.core.runtime.Platform
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Captures the real desktop. Runs where a backend exists and capture is allowed; on macOS a
 * runner without Screen Recording rights only checks enumeration.
 */
class ScreenCaptureLiveTest {
    private fun assumeCapturable() {
        assumeTrue(ScreenCapture.isSupported, "no capture backend")
        assumeTrue(ScreenCapture.backend != CaptureBackend.XdgDesktopPortal, "portal is interactive")
        assumeTrue(ScreenCapture.displays().isNotEmpty(), "no display")
        val permission = ScreenCapture.permissionStatus()
        assumeTrue(
            permission == CapturePermission.Granted || permission == CapturePermission.NotRequired,
            "$permission",
        )
    }

    @Test
    fun `backend matches the platform`() {
        val expected =
            when (Platform.Current) {
                Platform.Windows -> setOf(CaptureBackend.Gdi)
                Platform.MacOS -> setOf(CaptureBackend.ScreenCaptureKit, CaptureBackend.CoreGraphics)
                Platform.Linux -> setOf(CaptureBackend.X11, CaptureBackend.XdgDesktopPortal, CaptureBackend.Unavailable)
                Platform.Unknown -> setOf(CaptureBackend.Unavailable)
            }
        assertTrue(ScreenCapture.backend in expected, "${ScreenCapture.backend}")
    }

    @Test
    fun `displays are consistent`() {
        assumeCapturable()
        val displays = ScreenCapture.displays()
        assertEquals(1, displays.count { it.isPrimary }, "$displays")
        assertTrue(displays.first().isPrimary)
        assertEquals(displays.size, displays.map { it.id }.toSet().size, "duplicate ids: $displays")
        for (d in displays) {
            assertTrue(d.widthPx > 0 && d.heightPx > 0, "$d")
            assertTrue(d.scaleFactor >= 1f, "$d")
        }
    }

    @Test
    fun `full capture has the display's pixel size`() {
        assumeCapturable()
        for (display in ScreenCapture.displays()) {
            val image = ScreenCapture.captureDisplay(display)
            assertEquals(display.widthPx, image.width, "$display")
            assertEquals(display.heightPx, image.height, "$display")
            assertTrue(image.toArgbArray().all { it ushr 24 == 0xFF }, "alpha must be opaque")
        }
    }

    @Test
    fun `region is a crop of the full capture`() {
        assumeCapturable()
        val display = ScreenCapture.primaryDisplay()!!
        // Static content is not guaranteed; compare the region against a capture taken right after.
        val region = CaptureRegion(display.widthPx / 4, display.heightPx / 4, 97, 53)
        val part = ScreenCapture.captureDisplay(display, region)
        assertEquals(97, part.width)
        assertEquals(53, part.height)
        val full = ScreenCapture.captureDisplay(display).crop(region)
        val same = part.toArgbArray().zip(full.toArgbArray()).count { (a, b) -> a == b }
        assertTrue(same > part.width * part.height * 9 / 10, "region differs from the full capture: $same")
    }

    @Test
    fun `regions are clipped and empty intersections rejected`() {
        assumeCapturable()
        val display = ScreenCapture.primaryDisplay()!!
        val clipped = ScreenCapture.captureDisplay(display, CaptureRegion(display.widthPx - 10, -5, 50, 20))
        assertEquals(10, clipped.width)
        assertEquals(15, clipped.height)
        val outside = CaptureRegion(display.widthPx, 0, 10, 10)
        val error = assertFailsWith<ScreenCaptureException> { ScreenCapture.captureDisplay(display, outside) }
        assertEquals(CaptureFailure.InvalidRegion, error.failure)
        val far = CaptureRegion(Int.MAX_VALUE - 5, Int.MAX_VALUE - 5, 5, 5)
        assertEquals(
            CaptureFailure.InvalidRegion,
            assertFailsWith<ScreenCaptureException> { ScreenCapture.captureDisplay(display, far) }.failure,
        )
        val negative = CaptureRegion(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(
            CaptureFailure.InvalidRegion,
            assertFailsWith<ScreenCaptureException> { ScreenCapture.captureDisplay(display, negative) }.failure,
        )
    }

    @Test
    fun `unknown display and window are reported`() {
        assumeCapturable()
        val real = ScreenCapture.primaryDisplay()!!
        val ghost = CaptureDisplay("nucleus-no-such-display", "ghost", real.bounds, 10, 10, 1f, false)
        assertEquals(
            CaptureFailure.DisplayNotFound,
            assertFailsWith<ScreenCaptureException> { ScreenCapture.captureDisplay(ghost) }.failure,
        )
        for (id in listOf(0L, 1L, 0x7FFF_FFF0L, -1L)) {
            assertEquals(
                CaptureFailure.WindowNotFound,
                assertFailsWith<ScreenCaptureException> { ScreenCapture.captureWindow(id) }.failure,
                "window $id",
            )
        }
    }

    @Test
    fun `cursor capture stays within the display size`() {
        assumeCapturable()
        val display = ScreenCapture.primaryDisplay()!!
        val image = ScreenCapture.captureDisplay(display, includeCursor = true)
        assertEquals(display.widthPx, image.width)
    }

    @Test
    fun `concurrent random captures`() {
        assumeCapturable()
        val displays = ScreenCapture.displays()
        val pool = Executors.newFixedThreadPool(8)
        val done = AtomicInteger()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(8) { worker ->
            pool.execute {
                val random = Random(worker)
                repeat(ITERATIONS) {
                    val display = displays[random.nextInt(displays.size)]
                    val x = random.nextInt(-200, display.widthPx + 200)
                    val y = random.nextInt(-200, display.heightPx + 200)
                    val region = CaptureRegion(x, y, random.nextInt(1, 400), random.nextInt(1, 400))
                    try {
                        val image = ScreenCapture.captureDisplay(display, region, includeCursor = random.nextBoolean())
                        check(image.width <= region.width && image.height <= region.height) { "$region -> $image" }
                    } catch (e: ScreenCaptureException) {
                        if (e.failure != CaptureFailure.InvalidRegion) failures += e
                    } catch (e: Throwable) {
                        failures += e
                    }
                    done.incrementAndGet()
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.MINUTES))
        failures.peek()?.let { throw AssertionError("${failures.size} failures", it) }
        assertEquals(8 * ITERATIONS, done.get())
    }

    private companion object {
        const val ITERATIONS = 150
    }
}
