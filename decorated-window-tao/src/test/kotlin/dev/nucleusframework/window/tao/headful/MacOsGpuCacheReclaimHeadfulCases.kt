package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.scene.GPU_REST_PURGE_DELAY_MS
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * #638: the Skia GPU resource cache a scene fills while it animates must come
 * back once the window is at rest.
 *
 * The scene stacks blurred offscreen layers whose sizes change every frame, so
 * each frame mints scratch at sizes later frames may not reuse — the shape of a
 * Material animation's layer churn, condensed — plus a fresh image upload per
 * frame. The
 * GPU memory this process holds is read before, right after, and once the
 * window is at rest.
 */
internal object MacOsGpuCacheReclaimHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            reclaimCase("a window at rest returns the GPU cache its animation filled") {
                // Visible and still: only the rest delay can bring it back.
                settle(GPU_REST_PURGE_DELAY_MS + RECLAIM_SLACK_MS)
            },
            reclaimCase("a minimized window returns the GPU cache its animation filled at once") {
                // Read well before the rest delay: only the minimize can have reclaimed it.
                window.setMinimized(true)
                awaitUntil("window minimized") { window.isMinimized }
                settle(RECLAIM_SLACK_MS)
            },
        )

    private fun skipReason(): String? =
        if (Platform.Current != Platform.MacOS) "macOS only: reads MTLDevice.currentAllocatedSize" else null

    /**
     * Animates [ChurnScene] for [ANIMATION_FRAMES] frames, runs [toRest], and
     * requires at least half of what the animation added to be back.
     */
    private fun reclaimCase(
        title: String,
        toRest: suspend TaoWindowTestScope.() -> Unit,
    ): TaoWindowTestCase {
        val animating = mutableStateOf(false)
        val frames = mutableIntStateOf(0)
        return TaoWindowTestCase(
            name = "#638 $title",
            timeoutMillis = 60_000L,
            skip = ::skipReason,
            size = DpSize(WINDOW_DP.dp, WINDOW_DP.dp),
            content = {
                if (animating.value) {
                    LaunchedEffect(Unit) {
                        while (true) withFrameNanos { frames.intValue++ }
                    }
                }
                ChurnScene(frames.intValue, animating.value)
            },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            settle(SETTLE_MS)
            val before = gpuAllocatedBytes()
            animating.value = true
            awaitUntil("animation ran $ANIMATION_FRAMES frames") { frames.intValue >= ANIMATION_FRAMES }
            animating.value = false
            settle(SETTLE_MS)
            val afterAnimation = gpuAllocatedBytes()
            toRest()
            val atRest = gpuAllocatedBytes()
            if (window.isMinimized) {
                window.setMinimized(false)
                window.focus()
                awaitUntil("window restored") { !window.isMinimized }
            }
            println("[#638] GPU MB: before=${before.mb()} afterAnimation=${afterAnimation.mb()} atRest=${atRest.mb()}")
            val grown = afterAnimation - before
            check(grown >= MIN_GROWTH_BYTES) {
                "the animation did not grow the GPU cache (${grown.mb()} MB): the case measures nothing"
            }
            val returned = afterAnimation - atRest
            check(returned * 2 >= grown) {
                "${returned.mb()} MB of the ${grown.mb()} MB the animation added came back"
            }
        }
    }

    /**
     * [LAYERS] stacked offscreen layers, each blurred and each at a size no
     * other frame shares while animating: every frame asks Skia for
     * render-target scratch in a size bin it may not have yet, so the cache
     * climbs toward its budget. A static stack at rest.
     */
    @Composable
    private fun ChurnScene(
        frame: Int,
        animating: Boolean,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (animating) {
                // A fresh raster image per frame: one uniquely keyed texture
                // upload each, cached by Skia after the image itself is gone.
                val image = remember(frame) { frameImage(frame) }
                Canvas(Modifier.fillMaxSize()) { drawImage(image) }
            }
            repeat(LAYERS) { layer ->
                val step = if (animating) (frame * (layer + 1) * PRIME) % SIZE_STEPS else layer
                val w = MIN_BOX_DP + step * STEP_DP
                val h = MIN_BOX_DP + ((step * PRIME) % SIZE_STEPS) * STEP_DP
                Box(
                    Modifier
                        .size(w.dp, h.dp)
                        .graphicsLayer {
                            alpha = 0.5f
                            compositingStrategy = CompositingStrategy.Offscreen
                        }.blur((4 + step % 32).dp)
                        .background(Color(0xFF000000 or (0x3F51B5L + layer * 0x203040L))),
                )
            }
        }
    }

    /** Immutable, so Skia uploads it once under the image's unique key. */
    private fun frameImage(frame: Int): ImageBitmap {
        val pixels = ByteArray(IMAGE_PX * IMAGE_PX * 4) { (it + frame).toByte() }
        return Image
            .makeRaster(ImageInfo.makeN32Premul(IMAGE_PX, IMAGE_PX), pixels, IMAGE_PX * 4)
            .toComposeImageBitmap()
    }

    /**
     * `MTLDevice.currentAllocatedSize` of the system default device — every
     * byte this process holds on the GPU, Skia's cache included. Read through
     * FFM so the probe needs no native code of its own; on Apple Silicon there
     * is one device, the one the scene hosts render with.
     */
    private fun gpuAllocatedBytes(): Long = MetalAllocatedSize.read()

    private object MetalAllocatedSize {
        private val linker = Linker.nativeLinker()
        private val metal =
            SymbolLookup.libraryLookup("/System/Library/Frameworks/Metal.framework/Metal", Arena.global())
        private val objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global())
        private val device: MemorySegment =
            linker
                .downcallHandle(
                    metal.find("MTLCreateSystemDefaultDevice").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS),
                ).invokeWithArguments() as MemorySegment
        private val selector: MemorySegment =
            linker
                .downcallHandle(
                    objc.find("sel_registerName").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                ).invokeWithArguments(Arena.global().allocateFrom("currentAllocatedSize")) as MemorySegment
        private val msgSend =
            linker.downcallHandle(
                objc.find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            )

        fun read(): Long = msgSend.invokeWithArguments(device, selector) as Long
    }

    private fun Long.mb(): Long = this / (1024 * 1024)

    private const val WINDOW_DP = 1000
    private const val IMAGE_PX = 512
    private const val LAYERS = 16
    private const val PRIME = 37
    private const val MIN_BOX_DP = 100
    private const val STEP_DP = 8
    private const val SIZE_STEPS = 110
    private const val ANIMATION_FRAMES = 330
    private const val SETTLE_MS = 500L
    private const val RECLAIM_SLACK_MS = 1_000L
    private const val MIN_GROWTH_BYTES = 20L * 1024 * 1024
}
