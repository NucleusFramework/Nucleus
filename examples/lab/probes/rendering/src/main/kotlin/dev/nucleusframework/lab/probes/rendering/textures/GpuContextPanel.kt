package dev.nucleusframework.lab.probes.rendering.textures

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.SpecimenFrame
import dev.nucleusframework.lab.probes.rendering.common.FrameMeter
import dev.nucleusframework.window.tao.TaoGpuRenderContext
import dev.nucleusframework.window.tao.TaoOpenGlRenderContext
import dev.nucleusframework.window.tao.rememberTaoGpuRenderContext
import kotlinx.coroutines.isActive
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.cos
import kotlin.math.sin

private const val TARGET_WIDTH = 320
private const val TARGET_HEIGHT = 240

/** The scene's GPU context as this surface sees it: `null` until the surface is up. */
data class GpuContextInfo(
    val backend: String,
    val skiaContextId: String,
)

/**
 * `TaoGpuRenderContext` (#478), the opposite direction of `TextureView`: an in-process
 * renderer draws into a target allocated on the scene's **own** Skia context, under the
 * published access scope, and the scene samples the snapshot — no shared handle, no
 * second device, no per-frame copy. [onContext] reports which context this surface got.
 */
@Composable
fun GpuContextPanel(
    meter: FrameMeter,
    onContext: (GpuContextInfo?) -> Unit,
    size: Dp = 320.dp,
) {
    val context = rememberTaoGpuRenderContext()
    LaunchedEffect(context) {
        onContext(
            context?.let {
                GpuContextInfo(it.backend.toString(), Integer.toHexString(System.identityHashCode(it.skiaContext)))
            },
        )
    }
    if (context == null) return

    // Keyed on the context: a rebuilt context (window detach, Wayland hide/show) recreates the renderer.
    val renderer = remember(context) { SceneContextRenderer(context) }
    var frame by remember(context) { mutableStateOf<Image?>(null) }
    DisposableEffect(renderer) { onDispose { renderer.close() } }
    LaunchedEffect(renderer) {
        var tick = 0
        while (isActive) {
            // Inside the frame callback the GL context is bindable; after it, the swap may hold it.
            val next = withFrameNanos { renderer.renderFrame(tick) } ?: continue
            frame?.let(renderer::retire)
            frame = next
            tick++
        }
    }
    SpecimenFrame(Modifier.width(size)) {
        Canvas(Modifier.size(size, size * 0.75f)) {
            val image = frame ?: return@Canvas
            meter.tick()
            drawIntoCanvas { it.skiaCanvas.drawImageRect(image, Rect.makeWH(this.size.width, this.size.height)) }
        }
    }
}

/**
 * One persistent render target on the scene's context, one snapshot per frame. A snapshot
 * may still be referenced by an in-flight recorded frame (macOS records on the main thread
 * and replays on the render thread), so retired ones are closed two frames later.
 */
private class SceneContextRenderer(
    private val context: TaoGpuRenderContext,
) : AutoCloseable {
    private var surface: Surface? = null
    private val retired = ArrayDeque<Image>()
    private val paint = Paint()

    private fun <T> withGpuAccess(action: () -> T): T? =
        when (context) {
            is TaoOpenGlRenderContext -> context.withContextCurrent(action)
            else -> context.runOnGpuThread(action)
        }

    fun renderFrame(tick: Int): Image? =
        withGpuAccess {
            val target =
                surface ?: Surface
                    .makeRenderTarget(context.skiaContext, false, ImageInfo.makeN32Premul(TARGET_WIDTH, TARGET_HEIGHT))
                    .also { surface = it }
            val canvas = target.canvas
            canvas.clear(Color.hsv((tick % 360).toFloat(), 0.55f, 0.35f).toArgb())
            paint.color = 0xFFFFFFFF.toInt()
            val cx = TARGET_WIDTH / 2f + TARGET_WIDTH / 3f * cos(tick / 30.0).toFloat()
            val cy = TARGET_HEIGHT / 2f + TARGET_HEIGHT / 3f * sin(tick / 30.0).toFloat()
            canvas.drawCircle(cx, cy, 24f, paint)
            paint.color = 0x80FFFFFF.toInt()
            canvas.drawRect(Rect.makeXYWH((tick * 3f) % TARGET_WIDTH, 0f, 12f, TARGET_HEIGHT.toFloat()), paint)
            target.flushAndSubmit()
            val snapshot = target.makeImageSnapshot()
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
