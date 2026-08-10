package dev.nucleusframework.sampletao

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.window.tao.TaoGpuRenderContext
import dev.nucleusframework.window.tao.TaoOpenGlRenderContext
import dev.nucleusframework.window.tao.rememberTaoGpuRenderContext
import kotlinx.coroutines.isActive
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

private const val RT_W = 320
private const val RT_H = 240

/**
 * `TaoGpuRenderContext` demo (issue #478): the opposite ownership direction of
 * `TextureView`. Instead of a foreign producer importing a shareable buffer,
 * an in-process renderer allocates its render target **on the scene's own
 * Skia context** ([Surface.makeRenderTarget] against `skiaContext`), draws
 * into it under the published access scope (`runOnGpuThread` on Metal,
 * `withContextCurrent` on OpenGL), and the scene samples the snapshot with no
 * shared handle, no second GPU device, and no per-frame copy — the contract
 * external engines such as MapLibre Compose build on.
 */
@Suppress("FunctionNaming", "MagicNumber")
@Composable
fun GpuContextSection(
    label: TextStyle,
    compact: Boolean = false,
) {
    val renderContext = rememberTaoGpuRenderContext()

    if (!compact) {
        BasicText(
            text = "GPU render context — in-process renderer on the scene's own device:",
            style = label,
        )
    }
    if (renderContext == null) {
        BasicText("rememberTaoGpuRenderContext() → null (surface not up)", style = label)
        return
    }
    // The skiaContext identity is the interesting bit in compact mode: a tray
    // panel owns a private context on macOS/Linux (different id than the
    // window's), while Windows shares one per surface owner.
    BasicText(
        text =
            "backend=${renderContext.backend} · " +
                "skiaContext@${Integer.toHexString(System.identityHashCode(renderContext.skiaContext))}" +
                if (compact) "" else " · ${RT_W}x$RT_H private render target, snapshot sampled zero-copy",
        style = label,
    )

    // Keyed on the context instance: a rebuilt context (window detach, Wayland
    // hide/show) publishes a new instance, which recreates the renderer — the
    // invalidation contract of [TaoGpuRenderContext].
    val renderer = remember(renderContext) { SceneContextRenderer(renderContext) }
    var frame by remember(renderContext) { mutableStateOf<Image?>(null) }
    DisposableEffect(renderer) {
        onDispose { renderer.close() }
    }
    LaunchedEffect(renderer) {
        var tick = 0
        while (isActive) {
            // Render inside the frame callback: it runs during the scene's
            // render pass, when the swap thread is idle and the GL context is
            // bindable. A post-frame continuation would race the blocking
            // eglSwapBuffers on Linux and lose almost every frame
            // (withContextCurrent → null → the animation crawls).
            val next = withFrameNanos { renderer.renderFrame(tick) } ?: continue
            frame?.let(renderer::retire)
            frame = next
            tick++
        }
    }

    Canvas(
        Modifier
            .size(if (compact) 160.dp else 320.dp, if (compact) 120.dp else 240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1F2630)),
    ) {
        val image = frame ?: return@Canvas
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawImageRect(
                image,
                Rect.makeWH(size.width, size.height),
            )
        }
    }
    if (!compact) {
        BasicText(
            text = "animated by Skia draws issued through the published context",
            style = TextStyle(color = Color(0xFF6E7480), fontSize = 10.sp),
        )
    }
}

/**
 * A miniature in-process renderer: one persistent GPU render target allocated
 * on the scene's Skia context, one snapshot per frame.
 *
 * Snapshot lifetime: the image handed out for frame N may still be referenced
 * by an in-flight recorded frame (macOS records on the main thread and replays
 * on the render thread), so retired snapshots are closed inside a *later*
 * frame's GPU access scope — by then the render thread has serially moved past
 * every draw that referenced them.
 */
@Suppress("MagicNumber")
private class SceneContextRenderer(
    private val renderContext: TaoGpuRenderContext,
) : AutoCloseable {
    private var surface: Surface? = null
    private val retired = ArrayDeque<Image>()
    private val paint = Paint()

    /**
     * Runs [action] with safe access to the scene's GPU context: under the
     * bound GL context on OpenGL backends, on the Skia context's render
     * thread on Metal.
     */
    private fun <T> withGpuAccess(action: () -> T): T? =
        when (renderContext) {
            is TaoOpenGlRenderContext -> renderContext.withContextCurrent(action)
            else -> renderContext.runOnGpuThread(action)
        }

    /** Draws one frame into the private target and returns its snapshot. */
    fun renderFrame(tick: Int): Image? =
        withGpuAccess {
            val target =
                surface ?: Surface
                    .makeRenderTarget(
                        renderContext.skiaContext,
                        false,
                        ImageInfo.makeN32Premul(RT_W, RT_H),
                    ).also { surface = it }

            val canvas = target.canvas
            val hue = (tick % 360).toFloat()
            canvas.clear(
                androidx.compose.ui.graphics.Color
                    .hsv(hue, 0.55f, 0.35f)
                    .toArgb(),
            )
            paint.color = 0xFFFFFFFF.toInt()
            val cx = RT_W / 2f + (RT_W / 3f) * kotlin.math.cos(tick / 30.0).toFloat()
            val cy = RT_H / 2f + (RT_H / 3f) * kotlin.math.sin(tick / 30.0).toFloat()
            canvas.drawCircle(cx, cy, 24f, paint)
            paint.color = 0x80FFFFFF.toInt()
            canvas.drawRect(Rect.makeXYWH((tick * 3f) % RT_W, 0f, 12f, RT_H.toFloat()), paint)
            target.flushAndSubmit()

            val snapshot = target.makeImageSnapshot()
            // Two frames is enough headroom: frame N-2's replay finished before
            // this scope could run.
            while (retired.size > 2) retired.removeFirst().close()
            snapshot
        }

    fun retire(image: Image) {
        retired.addLast(image)
    }

    override fun close() {
        withGpuAccess {
            while (retired.isNotEmpty()) retired.removeFirst().close()
            surface?.close()
            surface = null
        }
        paint.close()
    }
}
