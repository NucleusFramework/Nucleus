package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.Rect as SkRect
import org.jetbrains.skia.Surface as SkSurface

/**
 * #638 — the Skia GPU resource cache of a scene host is never given back once
 * the window stops drawing.
 *
 * Skia evicts only when a new allocation would exceed the budget, so whatever a
 * busy stretch uploaded stays resident at its high-water mark for the life of
 * the process. The case fills the cache with textures of images that are still
 * alive (a gallery scrolled past, a carousel that moved on) and are therefore
 * unlocked but resident, goes static, minimizes the window — the moment nothing
 * is visible and a re-raster on restore costs nothing anyone can see — and
 * expects the cache back.
 *
 * Measured as the process' own GPU memory where the driver reports one
 * (NVIDIA, through `nvidia-smi`), else as RSS, which sees textures where the
 * driver keeps them in host RAM (Mesa llvmpipe, the CI Xvfb leg — RSS needs the
 * fixed `MALLOC_MMAP_THRESHOLD_` the task sets, or freed textures stay in the
 * malloc heap). Where neither sees them, the fill phase barely moves the
 * figure and the case reports itself inconclusive instead of asserting on
 * noise.
 */
internal object GpuCacheReclaimHeadfulCases {
    fun all(): List<TaoWindowTestCase> {
        val shown = mutableIntStateOf(NONE)
        val drawn = AtomicInteger(NONE - 1)
        val images = arrayOfNulls<SkImage>(IMAGE_COUNT)
        return listOf(
            TaoWindowTestCase(
                "minimizing a window reclaims its GPU resource cache (#638)",
                timeoutMillis = CASE_TIMEOUT_MS,
                skip = {
                    // The footprint probes (nvidia-smi, /proc) are Linux's; the
                    // Metal and ANGLE hosts run the same minimize purge
                    // without a per-process GPU figure to assert on.
                    if (Platform.Current != Platform.Linux) "per-process GPU accounting is Linux only" else null
                },
                size = DpSize(WINDOW_DP.dp, WINDOW_DP.dp),
                // The suite's default backdrop would take the whole window and
                // leave this content zero-sized — every draw culled, nothing
                // ever uploaded.
                paintDefaultBackground = false,
                content = {
                    val index = shown.intValue
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(BACKDROP_ARGB))
                            .drawBehind {
                                images.getOrNull(index)?.let { image ->
                                    drawIntoCanvas {
                                        it.nativeCanvas.drawImageRect(image, SkRect.makeWH(size.width, size.height))
                                    }
                                }
                                drawn.set(index)
                            },
                    )
                },
            ) {
                awaitUntil("window mapped") { window.hasRealFramePx() }
                awaitUntil("first frame drawn") { drawn.get() == NONE }

                // Raster pixels first, so the baseline already holds them and
                // the fill below only measures what the GPU side adds.
                repeat(IMAGE_COUNT) { images[it] = solidImage(it) }
                settle()
                val baseline = gpuFootprintBytes()

                for (i in 0 until IMAGE_COUNT) {
                    shown.intValue = i
                    awaitUntil("image $i drawn") { drawn.get() == i }
                }
                shown.intValue = NONE
                awaitUntil("static frame drawn") { drawn.get() == NONE }
                settle(STATIC_SETTLE_MS)
                val atRest = gpuFootprintBytes()

                val uploaded = IMAGE_COUNT.toLong() * IMAGE_PX * IMAGE_PX * BYTES_PER_PIXEL
                val growth = atRest - baseline
                if (growth < uploaded / 2) {
                    System.err.println(
                        "[tao-headful] INCONCLUSIVE #638: filling the cache moved the footprint by " +
                            "${growth.mib()} MiB for ${uploaded.mib()} MiB of textures — neither nvidia-smi " +
                            "nor RSS sees this driver's textures (GL drivers mapped: ${glDrivers()})",
                    )
                    return@TaoWindowTestCase
                }

                window.setMinimized(true)
                awaitUntil("window minimized") { window.isMinimized }
                settle(MINIMIZED_SETTLE_MS)
                val minimized = gpuFootprintBytes()
                window.setMinimized(false)
                awaitUntil("window restored") { !window.isMinimized }

                val reclaimed = atRest - minimized
                System.err.println(
                    "[tao-headful] #638 footprint baseline=${baseline.mib()} atRest=${atRest.mib()} " +
                        "minimized=${minimized.mib()} MiB (reclaimed ${reclaimed.mib()} of ${growth.mib()} MiB)",
                )
                check(reclaimed >= growth / 2) {
                    "minimizing gave back ${reclaimed.mib()} MiB of the ${growth.mib()} MiB the GPU cache " +
                        "grew by — the cache stays at its high-water mark"
                }
            },
        )
    }

    private fun solidImage(seed: Int): SkImage {
        // A raster image held alive, like a decoded picture an app keeps
        // around: Skia keys its upload to the image and keeps the texture —
        // unlocked, but resident — for as long as the image lives. A distinct
        // colour each, so no two uploads can be shared.
        val surface = SkSurface.makeRasterN32Premul(IMAGE_PX, IMAGE_PX)
        surface.canvas.clear((COLOR_BASE + seed * COLOR_STEP).toInt())
        return surface.makeImageSnapshot().also { surface.close() }
    }

    /**
     * What the textures cost the process: the driver's own per-process
     * figure where there is one (NVIDIA keeps them in VRAM, invisible to RSS),
     * otherwise RSS (Mesa llvmpipe keeps them in host memory).
     */
    private fun gpuFootprintBytes(): Long = nvidiaGpuMemoryBytes() ?: rssBytes()

    private fun nvidiaGpuMemoryBytes(): Long? {
        val output =
            runCatching {
                val process = ProcessBuilder("nvidia-smi", "-q", "-d", "PIDS").redirectErrorStream(true).start()
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .also { process.waitFor() }
            }.getOrNull() ?: return null
        val pid = ProcessHandle.current().pid().toString()
        val lines = output.lines()
        val at =
            lines.indexOfFirst {
                it.substringBefore(':').trim() == "Process ID" &&
                    it.substringAfter(':').trim() == pid
            }
        if (at < 0) return null
        return lines
            .drop(at + 1)
            .takeWhile { !it.contains("Process ID") }
            .firstOrNull { it.contains("Used GPU Memory") }
            ?.substringAfter(':')
            ?.trim()
            ?.removeSuffix("MiB")
            ?.trim()
            ?.toLongOrNull()
            ?.times(KIB * KIB)
    }

    private fun rssBytes(): Long =
        File("/proc/self/status")
            .readLines()
            .first { it.startsWith("VmRSS:") }
            .split(Regex("\\s+"))[1]
            .toLong() * KIB

    private fun glDrivers(): String =
        File("/proc/self/maps")
            .readLines()
            .mapNotNull { line -> line.substringAfterLast('/', "").takeIf { DRIVER_HINTS.any(it::contains) } }
            .distinct()
            .joinToString()

    private fun Long.mib(): Long = this / (KIB * KIB)

    private val DRIVER_HINTS = listOf("gallium", "dri", "nvidia", "mesa", "vulkan", "lvp")
    private const val NONE = -1
    private const val IMAGE_COUNT = 48
    private const val IMAGE_PX = 1024
    private const val BYTES_PER_PIXEL = 4
    private const val KIB = 1024L
    private const val WINDOW_DP = 400
    private const val STATIC_SETTLE_MS = 1_000L
    private const val MINIMIZED_SETTLE_MS = 1_500L
    private const val CASE_TIMEOUT_MS = 120_000L
    private const val BACKDROP_ARGB = 0xFF1E1F22
    private const val COLOR_BASE = 0xFF102030L
    private const val COLOR_STEP = 0x050301L
}
