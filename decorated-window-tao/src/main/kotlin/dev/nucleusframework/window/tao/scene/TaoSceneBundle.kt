package dev.nucleusframework.window.tao.scene

import androidx.compose.runtime.Recomposer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.scene.PlatformLayersComposeScene
import androidx.compose.ui.scene.SingleComposeSceneRenderingScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.WindowExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.Canvas
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
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
 * separate `invalidateLayout` / `invalidateDraw` callbacks. Compose Desktop's
 * AWT path drives those three steps through [SingleComposeSceneRenderingScope],
 * which swallows layout/draw invalidations raised during the in-flight frame
 * and re-arms [requestFrame] only if the scene is still dirty afterwards.
 * Driving the steps by hand (and wiring `invalidateLayout`/`invalidateDraw`
 * straight to [requestFrame]) can schedule a second frame while
 * `measureAndLayout` is still placing a tree that just remounted — Compose
 * 1.12's [androidx.compose.ui.spatial.RectManager] then throws
 * `LayoutNode not found in RectList` (nucleus-demo tab switches).
 *
 * Each bundle also carries a [RectManagerEdtGuard], which keeps the
 * RectManager's delayed dispatch off the AWT EDT (issue #551) — see that class
 * for the full story.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
internal class TaoSceneBundle(
    val scene: ComposeScene,
    val frameRecomposer: FrameRecomposer,
    private val renderingScope: SingleComposeSceneRenderingScope,
    private val edtGuard: RectManagerEdtGuard,
    /** Owns [edtGuard]'s debounce wakeups; cancelled with the scene. */
    private val guardScope: CoroutineScope,
    /** Flipped first thing in [close] — see [TaoSceneExceptionRouter]. */
    private val closed: AtomicBoolean,
    /** Installed in the scene's coroutine context; also stores [exceptionHandler]. */
    private val exceptionRouter: TaoSceneExceptionRouter,
    /** The owner's coalescing frame scheduler; re-armed after a swallowed frame failure. */
    private val requestFrame: () -> Unit,
) : AutoCloseable {
    /**
     * Catches what user code throws in this scene: [render] covers layout and
     * draw, the [TaoSceneExceptionRouter] in the scene's coroutine context
     * covers recomposition and everything else the scene's coroutines run.
     * Together they are the one seam every Tao host and popup layer shares.
     *
     * Installed from
     * [dev.nucleusframework.window.tao.LocalWindowExceptionHandlerFactory] by
     * whoever owns the bundle; `null` lets the failure escape, as it always did.
     */
    var exceptionHandler: WindowExceptionHandler?
        get() = exceptionRouter.handler
        set(value) {
            exceptionRouter.handler = value
        }

    /**
     * Whether the scene can still recompose.
     *
     * `Idle` and `PendingWork` are the two states in which a recomposition loop
     * is actually running. Everything else means there is none: in particular
     * `Recomposer.processCompositionError` records an error state, which
     * `deriveStateLocked` immediately derives to `Inactive` — so a composition
     * failure flips this to false the moment it happens, before the throw has
     * even finished unwinding. Such a scene still draws its last tree and still
     * hit-tests, but no state change will ever reach the screen again, so it
     * must not be mistaken for a recovered one.
     */
    val isRecomposerAlive: Boolean
        get() =
            when ((frameRecomposer.compositionContext as? Recomposer)?.currentState?.value) {
                Recomposer.State.Idle, Recomposer.State.PendingWork -> true
                // Not a Recomposer — cannot happen today; assume usable.
                null -> true
                else -> false
            }

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
        var swallowed = true
        exceptionHandler.catchExceptions {
            with(renderingScope) {
                scene.render(frameRecomposer, canvas.asComposeCanvas(), nanoTime)
            }
            edtGuard.afterFrame()
            swallowed = false
        }
        // The handler chose to continue: this frame's recording is incomplete,
        // so it is dropped and the scheduler re-armed. Without the re-arm the
        // scene can stay invalidated forever — the invalidation that produced
        // this frame was consumed by the pass that just aborted. Skipped once
        // the recomposer is gone: the next frame would be identical, so this
        // would be a repaint spin rather than a retry.
        if (swallowed && isRecomposerAlive) requestFrame()
    }

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        closed.set(true)
        guardScope.cancel()
        // A DisposableEffect onDispose throwing while the scene is being torn
        // down must not escalate to the app-fatal path (closing one window
        // would close the whole app — #622 review) nor skip
        // frameRecomposer.close().
        try {
            scene.close()
        } catch (t: Throwable) {
            bundleLogger.log(Level.SEVERE, "Unhandled exception during scene teardown", t)
        }
        frameRecomposer.close()
    }
}

private val bundleLogger: Logger = Logger.getLogger(TaoSceneBundle::class.java.name)

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
    val renderingScope = SingleComposeSceneRenderingScope(requestFrame)
    val closed = AtomicBoolean(false)
    // Every coroutine the scene owns carries the router: a recomposition
    // failure is offered to the window's handler first (#621) and falls
    // through to the app-fatal path (#622) when nothing swallows it.
    val exceptionRouter = TaoSceneExceptionRouter(closed)
    val sceneContext = coroutineContext + exceptionRouter
    val frameRecomposer = FrameRecomposer(sceneContext) { requestFrame() }
    val guardScope = CoroutineScope(sceneContext + SupervisorJob())
    val edtGuard = RectManagerEdtGuard(guardScope, requestFrame)
    val scene =
        CanvasLayersComposeScene(
            frameRecomposer = frameRecomposer,
            density = density,
            layoutDirection = layoutDirection,
            size = size,
            platformContext = platformContext.withEdtGuard(edtGuard),
            invalidateLayout = { renderingScope.onSceneInvalidation() },
            invalidateDraw = { renderingScope.onSceneInvalidation() },
        )
    return TaoSceneBundle(
        scene,
        frameRecomposer,
        renderingScope,
        edtGuard,
        guardScope,
        closed,
        exceptionRouter,
        requestFrame,
    ).also { bundle -> exceptionRouter.sceneIsAlive = { bundle.isRecomposerAlive } }
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
    val renderingScope = SingleComposeSceneRenderingScope(requestFrame)
    // See canvasLayersSceneBundle.
    val closed = AtomicBoolean(false)
    val exceptionRouter = TaoSceneExceptionRouter(closed)
    val sceneContext = coroutineContext + exceptionRouter
    val frameRecomposer = FrameRecomposer(sceneContext) { requestFrame() }
    val guardScope = CoroutineScope(sceneContext + SupervisorJob())
    val edtGuard = RectManagerEdtGuard(guardScope, requestFrame)
    val scene =
        PlatformLayersComposeScene(
            frameRecomposer = frameRecomposer,
            density = density,
            layoutDirection = layoutDirection,
            size = size,
            composeSceneContext =
                object : ComposeSceneContext by composeSceneContext {
                    override val platformContext: PlatformContext =
                        composeSceneContext.platformContext.withEdtGuard(edtGuard)
                },
            invalidateLayout = { renderingScope.onSceneInvalidation() },
            invalidateDraw = { renderingScope.onSceneInvalidation() },
        )
    return TaoSceneBundle(
        scene,
        frameRecomposer,
        renderingScope,
        edtGuard,
        guardScope,
        closed,
        exceptionRouter,
        requestFrame,
    ).also { bundle -> exceptionRouter.sceneIsAlive = { bundle.isRecomposerAlive } }
}

/**
 * Returns a [PlatformContext] view whose [PlatformContext.semanticsOwnerListener]
 * additionally registers every announced owner with [edtGuard].
 */
@OptIn(InternalComposeUiApi::class)
private fun PlatformContext.withEdtGuard(edtGuard: RectManagerEdtGuard): PlatformContext =
    object : PlatformContext by this {
        override val semanticsOwnerListener: PlatformContext.SemanticsOwnerListener =
            edtGuard.wrapListener(this@withEdtGuard.semanticsOwnerListener)
    }
