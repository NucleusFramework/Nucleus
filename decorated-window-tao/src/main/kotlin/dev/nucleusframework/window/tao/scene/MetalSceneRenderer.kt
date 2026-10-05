package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.render.MetalFrame
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin

/**
 * Stage-2 render split (AWT/skiko `dispatcherToBlockOn` pattern): the per-frame
 * Skia/Metal dance is divided into a **record** phase that runs on the macOS main
 * thread and a **replay/present** phase that runs on a dedicated background render
 * thread.
 *
 *  - [recordSceneToPicture] (main thread): drives Compose's measure/layout/draw
 *    into a [Picture] via a [PictureRecorder]. **No GPU, no native calls, no
 *    `DirectContext`.** Compose state is only ever touched here, on the scene's
 *    own (main) thread.
 *  - [replayPictureToFrame] (render thread): acquires a Metal drawable, wraps it
 *    in a Skia [Surface], replays the recorded [Picture], flushes, and presents.
 *    All `DirectContext`-bound work lives here so Skia's Metal context
 *    thread-affinity is respected.
 *
 * Splitting the frame this way keeps the GPU command encoding (`flushAndSubmit`)
 * and the potentially-blocking `nextDrawable` off the main thread, so the Tao
 * event loop stays responsive to input even under heavy per-frame GPU load —
 * matching how skiko's `SkiaLayer.update` records on the main thread while
 * `ContextHandler.draw()` replays on `dispatcherToBlockOn`.
 *
 * Skia's Metal API requires re-creating the [Surface] every frame because each
 * drawable wraps a different texture, so the per-frame allocations in
 * [replayPictureToFrame] are unavoidable.
 */
internal fun recordSceneToPicture(
    bundle: TaoSceneBundle,
    widthPx: Int,
    heightPx: Int,
    nanoTime: Long = System.nanoTime(),
    /**
     * Where the drawable sits in the coordinate space the scene draws in.
     * The window's own scene draws at the origin; a popup layer draws in
     * owner-window coordinates and passes its draw bounds
     * ([dev.nucleusframework.window.tao.popup.popupPictureCullRect]), because
     * Skia quick-rejects a picture whose cull rect misses the replay matrix.
     */
    cullRect: Rect = Rect.makeWH(widthPx.toFloat(), heightPx.toFloat()),
    /** See [TaoSceneBundle.render]. */
    beforeDraw: ((org.jetbrains.skia.Canvas) -> Unit)? = null,
): Picture =
    PictureRecorder().use { recorder ->
        // The cull bounds match the drawable size (physical pixels). The scene is
        // rendered at this size; the clear happens at replay time, not here.
        val canvas = recorder.beginRecording(cullRect)
        bundle.render(canvas, nanoTime, beforeDraw)
        // Closing the recorder here frees its native memory deterministically
        // (one recorder per frame — a GC-driven Cleaner would lag far behind);
        // the returned Picture owns its own native ref and survives the close.
        recorder.finishRecordingAsPicture()
    }

/**
 * Draws [picture] onto this canvas with its origin moved to [pictureOffset] —
 * the one step of [replayPictureToFrame] that is pure Skia, split out so it can
 * be exercised against a raster surface without a Metal device.
 *
 * The offset and the picture's cull rect are two halves of one contract: Skia
 * quick-rejects a picture whose cull rect, mapped through the current matrix,
 * misses the drawable, so a caller that translates here must record with a cull
 * rect expressed in the same space as the content
 * ([dev.nucleusframework.window.tao.popup.popupPictureCullRect]).
 */
internal fun Canvas.replayPicture(
    picture: Picture,
    pictureOffset: IntOffset,
) {
    translate(pictureOffset.x.toFloat(), pictureOffset.y.toFloat())
    drawPicture(picture)
}

/**
 * Replays a [picture] recorded by [recordSceneToPicture] into the attachment's
 * next Metal drawable and presents it. Must run on the render thread that owns
 * [directContext].
 *
 * If [NativeMetalBridge.nativeBeginFrame] returns null the function is a no-op
 * and returns `false` (the [present] lambda is **not** invoked — there is no
 * drawable to balance). If [Surface.makeFromBackendRenderTarget] returns null
 * the drawable is still presented (untextured) so the Metal command queue stays
 * balanced.
 *
 * [present] lets callers swap the default async present
 * (`NativeMetalBridge.nativePresent`) for the synchronous
 * `nativePresentWithInterop` path when AppKit subview mutations need to commit
 * atomically with the Compose frame. When a drawable was acquired, the lambda is
 * invoked exactly once — including from the failure-path `finally` — so callers
 * relying on it to drain side state (e.g. an interop transaction) don't need to
 * handle missed calls.
 *
 * @return `true` if a drawable was acquired and presented from the success path.
 */
internal fun replayPictureToFrame(
    attachmentHandle: Long,
    directContext: DirectContext,
    picture: Picture,
    clearColor: Int,
    /**
     * Where the picture's origin lands on the surface. A popup layer records
     * its scene in window coordinates and draws it into a surface rooted at
     * the layer's draw bounds, so it passes `-drawBounds.topLeft`.
     */
    pictureOffset: IntOffset = IntOffset.Zero,
    present: (handle: Long, drawablePtr: Long) -> Unit = { h, d ->
        NativeMetalBridge.nativePresent(h, d)
    },
): Boolean {
    val frame = NativeMetalBridge.nativeBeginFrame(attachmentHandle) ?: return false
    var presented = false
    try {
        val rt = BackendRenderTarget.makeMetal(frame.widthPx, frame.heightPx, frame.texturePtr)
        val surface =
            Surface.makeFromBackendRenderTarget(
                context = directContext,
                rt = rt,
                origin = SurfaceOrigin.TOP_LEFT,
                colorFormat = SurfaceColorFormat.BGRA_8888,
                colorSpace = ColorSpace.sRGB,
            ) ?: run {
                rt.close()
                return false
            }
        try {
            surface.canvas.clear(clearColor)
            surface.canvas.replayPicture(picture, pictureOffset)
            surface.flushAndSubmit(syncCpu = false)
            present(attachmentHandle, frame.drawablePtr)
            presented = true
        } finally {
            surface.close()
            rt.close()
        }
    } finally {
        // Drawable was retained in beginFrame — release via present to
        // balance even when wrapping or rendering failed.
        if (!presented) present(attachmentHandle, frame.drawablePtr)
    }
    return presented
}

/**
 * A frame recorded on the main thread, ready to be replayed + presented on the
 * render thread. Produced by overlay/popup surfaces ([TaoPopupSceneLayer])
 * and collected by [TaoComposeSceneHost] during
 * its record pass; the host replays them on its render thread after the main
 * scene.
 *
 * [isAlive] is re-checked on the render thread immediately before replay so a
 * surface disposed between record and replay (e.g. a sibling popup dismissed
 * during another popup's record) is skipped rather than replayed against a
 * closed [directContext] / freed attachment. [picture] is always closed by the
 * host after the replay attempt, alive or not.
 */
internal class TaoRecordedSurface(
    val attachmentHandle: Long,
    val directContext: DirectContext,
    val picture: Picture,
    val clearColor: Int,
    val present: (handle: Long, drawablePtr: Long) -> Unit = { h, d ->
        NativeMetalBridge.nativePresent(h, d)
    },
    val isAlive: () -> Boolean = { true },
    /** Translation applied before the picture is drawn — see [replayPictureToFrame]. */
    val pictureOffset: IntOffset = IntOffset.Zero,
)

/**
 * Partial redraw on the macOS host (#755), Chromium's `BufferQueue` model: a
 * `CAMetalLayer` recycles a few drawables, each still holding the frame last
 * drawn into it, so a frame repaints only what *its* drawable has missed —
 * the damage of every frame drawn since into the others — straight into the
 * drawable. No copy, no extra surface.
 *
 * The layer reports no buffer age, so the buffers are told apart by their
 * IOSurface ID ([NativeMetalBridge.nativeDrawableBufferState]), and the whole
 * thing rests on a recycled drawable keeping its pixels — what the platform
 * does but does not document. Wherever that is in doubt the frame repaints in
 * full: a buffer not seen before, an IOSurface purged since it was drawn,
 * a size change, a pause longer than [IDLE_RESET_NS] (when a purge is
 * likeliest), a failed frame. The `nucleus.tao.partialRedraw.verify` oracle
 * reads the drawable itself back, so it checks that assumption too.
 *
 * A frame that changed nothing acquires no drawable and presents nothing.
 *
 * Confined to the render thread that owns the [DirectContext].
 */
internal class MetalDrawableDamage {
    /**
     * Per buffer (IOSurface ID), what it has missed since it was last drawn;
     * no entry means unknown — repaint in full.
     */
    private val missed = HashMap<Int, IntRect>()

    /** Size the [missed] entries describe; another one starts over. */
    private var width = 0
    private var height = 0

    /** When a drawable was last presented; a long pause starts over. */
    private var lastPresentNs = 0L

    /** A frame that changed the picture did not reach the screen: the next one must present. */
    private var screenStale = false

    /** Alternates the `nucleus.tao.partialRedraw.tint` colour. */
    private var tintFrame = 0

    /** What the last [replay] repainted, `null` for everything — read by the debug stats. */
    var lastRepaint: IntRect? = null
        private set

    /** Forgets every buffer: the next frames repaint in full. */
    fun invalidate() {
        missed.clear()
    }

    /**
     * Replays [picture] — a frame of [widthPx]×[heightPx] whose [damage] is
     * what changed since the previous frame (`null`: unknown) — into the next
     * drawable and presents it. With [mustPresent] false, a frame that changed
     * nothing is not presented. Returns whether a drawable was presented;
     * [present] runs exactly when one was acquired, as in [replayPictureToFrame].
     */
    @Suppress("LongParameterList")
    fun replay(
        attachmentHandle: Long,
        directContext: DirectContext,
        picture: Picture,
        clearColor: Int,
        widthPx: Int,
        heightPx: Int,
        damage: IntRect?,
        mustPresent: Boolean,
        present: (handle: Long, drawablePtr: Long) -> Unit,
    ): Boolean {
        noteFrame(widthPx, heightPx, damage)
        val idle = damage != null && damage.isEmpty()
        if (idle && !screenStale && !mustPresent && !PartialRedraw.verify) {
            lastRepaint = IntRect.Zero
            return false
        }
        val frame = NativeMetalBridge.nativeBeginFrame(attachmentHandle)
        if (frame == null) {
            if (!idle) screenStale = true
            return false
        }
        var presented = false
        try {
            val buffer = bufferOf(frame)
            val repaint = buffer?.let(missed::get)?.takeUnless { it.coversMostOf(frame) }
            lastRepaint = repaint
            // Unknown until the frame is in: a draw that throws half-way leaves no frame at all.
            buffer?.let(missed::remove)
            BackendRenderTarget.makeMetal(frame.widthPx, frame.heightPx, frame.texturePtr).use { rt ->
                val surface =
                    Surface.makeFromBackendRenderTarget(
                        context = directContext,
                        rt = rt,
                        origin = SurfaceOrigin.TOP_LEFT,
                        colorFormat = SurfaceColorFormat.BGRA_8888,
                        colorSpace = ColorSpace.sRGB,
                    ) ?: return false
                surface.use {
                    draw(surface, picture, clearColor, repaint)
                    if (PartialRedraw.verify) verify(directContext, surface, picture, clearColor, repaint)
                    surface.flushAndSubmit(syncCpu = false)
                    if (buffer != null) missed[buffer] = IntRect.Zero
                    present(attachmentHandle, frame.drawablePtr)
                    presented = true
                    screenStale = false
                    lastPresentNs = System.nanoTime()
                }
            }
        } finally {
            // Balances nativeBeginFrame's retain, as in replayPictureToFrame.
            if (!presented) {
                present(attachmentHandle, frame.drawablePtr)
                screenStale = true
            }
        }
        return presented
    }

    /** Adds this frame's [damage] to what every known buffer has missed — or forgets them all. */
    private fun noteFrame(
        widthPx: Int,
        heightPx: Int,
        damage: IntRect?,
    ) {
        val paused = lastPresentNs != 0L && System.nanoTime() - lastPresentNs > IDLE_RESET_NS
        val resized = widthPx != width || heightPx != height
        if (!PartialRedraw.enabled || damage == null || paused || resized) {
            missed.clear()
            width = widthPx
            height = heightPx
            return
        }
        if (damage.isEmpty()) return
        for (entry in missed.entries) entry.setValue(entry.value.union(damage))
    }

    /**
     * The IOSurface ID of [frame]'s drawable, or `null` when its contents
     * cannot be trusted: no IOSurface, purged since it was drawn, or not at
     * the size the damage describes.
     */
    private fun bufferOf(frame: MetalFrame): Int? {
        if (frame.widthPx != width || frame.heightPx != height) {
            missed.clear()
            return null
        }
        val state = NativeMetalBridge.nativeDrawableBufferState(frame.drawablePtr)
        if (state < 0) return null
        val id = (state ushr 1).toInt()
        if (state and 1L != 0L) {
            missed.remove(id)
            return id
        }
        // A buffer never seen has no entry yet: repainted in full, then tracked.
        if (missed.size >= MAX_BUFFERS && id !in missed) missed.clear()
        return id
    }

    /** Repaints [repaint] (`null`: everything) of [surface] with the frame [picture]. */
    private fun draw(
        surface: Surface,
        picture: Picture,
        clearColor: Int,
        repaint: IntRect?,
    ) {
        val canvas = surface.canvas
        canvas.save()
        repaint?.let { canvas.clipRect(it.toSkiaRect()) }
        canvas.clear(clearColor)
        canvas.drawPicture(picture)
        if (repaint != null && PartialRedraw.debugTint) {
            org.jetbrains.skia.Paint().use { paint ->
                paint.color = PartialRedraw.DEBUG_TINTS[tintFrame++ and 1]
                canvas.drawRect(repaint.toSkiaRect(), paint)
            }
        }
        canvas.restore()
    }

    /**
     * `nucleus.tao.partialRedraw.verify`: draws [picture] again, in full, and
     * compares it with the drawable after a frame that repainted only
     * [repaint] — which also checks that the recycled drawable kept its
     * pixels. Needs `framebufferOnly` off, see [NativeMetalBridge.nativeSetFramebufferOnly].
     */
    private fun verify(
        directContext: DirectContext,
        surface: Surface,
        picture: Picture,
        clearColor: Int,
        repaint: IntRect?,
    ) {
        val info = ImageInfo.makeN32Premul(surface.width, surface.height)
        // Top-left like the drawable: the 3-argument overload is bottom-left,
        // where content that samples its own target renders flipped.
        val reference =
            Surface.makeRenderTarget(directContext, false, info, 0, SurfaceOrigin.TOP_LEFT, null) ?: return
        reference.use {
            reference.canvas.clear(clearColor)
            reference.canvas.drawPicture(picture)
            Bitmap().use { partial ->
                Bitmap().use { full ->
                    partial.allocPixels(info)
                    full.allocPixels(info)
                    if (surface.readPixels(partial, 0, 0) && reference.readPixels(full, 0, 0)) {
                        PartialRedrawVerifier.compare(partial, full, repaint)
                    }
                }
            }
        }
    }

    private fun IntRect.isEmpty(): Boolean = width <= 0 || height <= 0

    /** At least [FULL_REPAINT_PERCENT] of [frame]'s area: cheaper repainted in full. */
    private fun IntRect.coversMostOf(frame: MetalFrame): Boolean =
        width.toLong() * height * PERCENT >= FULL_REPAINT_PERCENT * frame.widthPx.toLong() * frame.heightPx

    private companion object {
        /**
         * A pause after which every buffer repaints in full: an idle window is
         * when the system is likeliest to have reclaimed a drawable's memory.
         */
        const val IDLE_RESET_NS = 1_000_000_000L

        /** More buffers than a layer ever cycles through (3, or 2): the pool was replaced. */
        const val MAX_BUFFERS = 4

        /**
         * A repaint covering this much of the drawable is done in full. A
         * partial frame has a fixed cost (~0.3 ms for a few pixels): measured
         * on Apple Silicon at 2560×1050, it breaks even with a full repaint
         * around 80 % and costs up to ~5 % more above, while it still saves
         * 6–26 % at 70 %.
         */
        const val FULL_REPAINT_PERCENT = 80L

        const val PERCENT = 100L
    }
}
