package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.scene.PlatformLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.skia.Canvas
import kotlin.coroutines.CoroutineContext

/**
 * Bundles a [ComposeScene] with the [FrameRecomposer] that drives its frame
 * loop.
 *
 * Compose 1.12 split the old `ComposeScene.render(canvas, nanoTime)` call
 * (which used to own recomposition, layout, and draw) into three steps —
 * `FrameRecomposer.performFrame` + `ComposeScene.measureAndLayout` +
 * `ComposeScene.draw` — and replaced the scene factories' former
 * `coroutineContext` / `invalidate` parameters with a [FrameRecomposer] plus
 * separate `invalidateLayout` / `invalidateDraw` callbacks. (Compose Desktop's
 * own AWT path wraps the same three calls in the module-private
 * `SingleComposeSceneRenderingScope.render`, which isn't visible to external
 * backends, so the Tao hosts drive them directly here.) This type keeps the
 * scene and its recomposer together so the Tao hosts can create, render, and
 * dispose a scene with a single object.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoSceneBundle(
    val scene: ComposeScene,
    val frameRecomposer: FrameRecomposer,
) : AutoCloseable {
    /**
     * Recomposes, lays out, and draws one frame into [canvas] — the drop-in
     * replacement for the pre-1.12 `scene.render(canvas.asComposeCanvas(), nanoTime)`.
     * [nanoTime] is fed to the recomposer's frame clock, so `withFrameNanos`
     * animations advance on the timestamp the caller paces to.
     */
    fun render(
        canvas: Canvas,
        nanoTime: Long,
    ) {
        frameRecomposer.performFrame(nanoTime)
        scene.measureAndLayout()
        scene.draw(canvas.asComposeCanvas())
    }

    override fun close() {
        scene.close()
        frameRecomposer.close()
    }
}

/**
 * Creates a [CanvasLayersComposeScene] wired to a fresh [FrameRecomposer].
 * [requestFrame] is invoked whenever the scene needs to be repainted
 * (recomposition, relayout, redraw, or a pending animation frame); callers
 * funnel it into their coalescing frame scheduler.
 */
@OptIn(InternalComposeUiApi::class)
internal fun canvasLayersSceneBundle(
    coroutineContext: CoroutineContext,
    density: Density,
    layoutDirection: LayoutDirection,
    size: IntSize? = null,
    platformContext: PlatformContext,
    requestFrame: () -> Unit,
): TaoSceneBundle {
    val frameRecomposer = FrameRecomposer(coroutineContext) { requestFrame() }
    val scene =
        CanvasLayersComposeScene(
            frameRecomposer = frameRecomposer,
            density = density,
            layoutDirection = layoutDirection,
            size = size,
            platformContext = platformContext,
            invalidateLayout = { requestFrame() },
            invalidateDraw = { requestFrame() },
        )
    return TaoSceneBundle(scene, frameRecomposer)
}

/**
 * Creates a [PlatformLayersComposeScene] wired to a fresh [FrameRecomposer].
 * See [canvasLayersSceneBundle].
 */
@OptIn(InternalComposeUiApi::class)
internal fun platformLayersSceneBundle(
    coroutineContext: CoroutineContext,
    density: Density,
    layoutDirection: LayoutDirection,
    size: IntSize? = null,
    composeSceneContext: ComposeSceneContext,
    requestFrame: () -> Unit,
): TaoSceneBundle {
    val frameRecomposer = FrameRecomposer(coroutineContext) { requestFrame() }
    val scene =
        PlatformLayersComposeScene(
            frameRecomposer = frameRecomposer,
            density = density,
            layoutDirection = layoutDirection,
            size = size,
            composeSceneContext = composeSceneContext,
            invalidateLayout = { requestFrame() },
            invalidateDraw = { requestFrame() },
        )
    return TaoSceneBundle(scene, frameRecomposer)
}
