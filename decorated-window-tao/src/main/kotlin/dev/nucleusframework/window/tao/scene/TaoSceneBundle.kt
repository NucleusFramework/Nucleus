package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.scene.PlatformLayersComposeScene
import androidx.compose.ui.scene.hasInvalidations
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.skia.Canvas
import kotlin.coroutines.CoroutineContext

/**
 * Bundles a [ComposeScene] with the [FrameRecomposer] that drives its frame
 * loop — Tao's stand-in for AWT `ComposeSceneMediator` +
 * `SingleComposeSceneRenderingScope`.
 *
 * ## Why this is not a verbatim AWT copy
 *
 * AWT paint order is the contract we **do** match:
 *
 * ```
 * performFrame() → measureAndLayout() → draw()
 * ```
 *
 * Do not drain host work, apply snapshots, or walk a11y between those
 * phases. AWT never does; doing so after a remount walks Compose 1.12's
 * RectList while it is torn (`IllegalArgumentException: LayoutNode N not
 * found in RectList`). Seen on nucleus-demo tab switches: a `when` body
 * remounts in the same frame as a sibling `graphicsLayer` update
 * (`replace()` / `onCoordinatorRectChanged`). Vanilla Compose Desktop
 * (AWT) does not crash — this is a Tao host bug, not an app workaround.
 *
 * AWT input **queueing** is the contract we **do not** match. Tao's
 * native loop delivers pointer events on the compose thread immediately;
 * [ComposeScene.sendPointerEvent] then calls `measureAndLayout` *before*
 * dispatching the event. Queuing clicks until the next vsync would add
 * up to a frame of latency and is a different product than this backend.
 * Until Compose stops throwing, [render] and [prepareForPointerInput]
 * force a root remasure (1px [ComposeScene.size] toggle, restored before
 * measure). If RectList still throws — including during
 * [FrameRecomposer.performFrame] `applyChanges` detach (`RectManager.remove`)
 * — skip layout/draw for this frame and request another. Do **not** remasure
 * after a failed detach: that measures a half-removed tree and throws
 * `LayoutNode should be attached to an owner` (Material3 `TopAppBar`).
 *
 * Remove the size toggle / skip-frame when Compose 1.12+ no longer throws
 * on remount + `graphicsLayer` in one frame. Keep the AWT paint order.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoSceneBundle(
    val scene: ComposeScene,
    val frameRecomposer: FrameRecomposer,
    private val requestFrame: () -> Unit,
) : AutoCloseable {
    @Volatile
    private var isRendering: Boolean = false

    fun onSceneInvalidation() {
        if (isRendering) return
        requestFrame()
    }

    /**
     * One host frame into [canvas], AWT paint order: recompose, then layout,
     * then draw. See the class KDoc for why host work must not run between
     * those phases and why a root remasure may precede layout.
     */
    fun render(
        canvas: Canvas,
        nanoTime: Long,
    ) {
        isRendering = true
        var skipPresent = false
        try {
            try {
                frameRecomposer.performFrame(nanoTime)
            } catch (error: Throwable) {
                if (!error.isTornComposeTree()) throw error
                // applyChanges detach hit a stale RectList entry. The tree
                // may be mid-remove — do not measure or draw it this frame.
                skipPresent = true
            }
            if (!skipPresent) {
                skipPresent = !measureAndLayoutRebuildingRectList()
            }
            if (!skipPresent) {
                try {
                    scene.draw(canvas.asComposeCanvas())
                } catch (error: Throwable) {
                    if (!error.isTornComposeTree()) throw error
                    skipPresent = true
                }
            }
        } finally {
            isRendering = false
        }
        if (skipPresent || scene.hasInvalidations()) {
            requestFrame()
        }
    }

    /**
     * Call immediately before every [ComposeScene.sendPointerEvent].
     *
     * Compose measures before it dispatches the event. After a remount that
     * has not been laid out yet, that measure hits the torn RectList
     * [render] would have rebuilt. This is the Tao-only gap versus AWT:
     * AWT almost never measures a remounted tree until the next paint.
     * We do not queue the click; we rebuild RectList first. No-op when
     * layout is already idle.
     */
    fun prepareForPointerInput() {
        forceRootRemeasureIfLayoutPending()
    }

    /** @return false if Compose's tree is torn and this frame must not draw. */
    private fun measureAndLayoutRebuildingRectList(): Boolean {
        forceRootRemeasureIfLayoutPending()
        return try {
            scene.measureAndLayout()
            true
        } catch (error: Throwable) {
            if (!error.isTornComposeTree()) throw error
            false
        }
    }

    /**
     * Marks the root measure-pending via a 1px [ComposeScene.size] toggle.
     * The owner only remasures when size actually changes; both assignments
     * differ from the current field, then the original size is restored so
     * [ComposeScene.measureAndLayout] never presents the bump.
     * Temporary: drop with the RectList workaround in the class KDoc.
     */
    private fun forceRootRemeasureIfLayoutPending() {
        if (!scene.hasPendingMeasureOrLayout) return
        forceRootRemeasure()
    }

    private fun forceRootRemeasure() {
        val size = scene.size ?: return
        if (size.width <= 0 || size.height <= 0) return
        scene.size = IntSize(size.width, size.height + 1)
        scene.size = size
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
    lateinit var bundle: TaoSceneBundle
    val frameRecomposer = FrameRecomposer(coroutineContext) { requestFrame() }
    val scene =
        CanvasLayersComposeScene(
            frameRecomposer = frameRecomposer,
            density = density,
            layoutDirection = layoutDirection,
            size = size,
            platformContext = platformContext,
            invalidateLayout = { bundle.onSceneInvalidation() },
            invalidateDraw = { bundle.onSceneInvalidation() },
        )
    bundle = TaoSceneBundle(scene, frameRecomposer, requestFrame)
    return bundle
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
    lateinit var bundle: TaoSceneBundle
    val frameRecomposer = FrameRecomposer(coroutineContext) { requestFrame() }
    val scene =
        PlatformLayersComposeScene(
            frameRecomposer = frameRecomposer,
            density = density,
            layoutDirection = layoutDirection,
            size = size,
            composeSceneContext = composeSceneContext,
            invalidateLayout = { bundle.onSceneInvalidation() },
            invalidateDraw = { bundle.onSceneInvalidation() },
        )
    bundle = TaoSceneBundle(scene, frameRecomposer, requestFrame)
    return bundle
}

/**
 * Compose 1.12 RectList / detach failures (see [TaoSceneBundle]).
 * Includes the follow-up `should be attached to an owner` after a
 * half-finished `applyChanges` remove.
 */
private fun Throwable.isTornComposeTree(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val message = current.message.orEmpty()
        if (
            message.contains("not found in RectList") ||
            message.contains("without valid parent index") ||
            message.contains("should be attached to an owner") ||
            message.contains("layout state is not idle")
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
