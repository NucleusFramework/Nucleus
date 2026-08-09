package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.ComposeScene
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
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
@OptIn(InternalComposeUiApi::class)
internal fun recordSceneToPicture(
    scene: ComposeScene,
    widthPx: Int,
    heightPx: Int,
    nanoTime: Long = System.nanoTime(),
): Picture =
    PictureRecorder().use { recorder ->
        // The cull bounds match the drawable size (physical pixels). The scene is
        // rendered at this size; the clear happens at replay time, not here.
        val canvas = recorder.beginRecording(Rect.makeWH(widthPx.toFloat(), heightPx.toFloat()))
        scene.render(canvas.asComposeCanvas(), nanoTime)
        // Closing the recorder here frees its native memory deterministically
        // (one recorder per frame — a GC-driven Cleaner would lag far behind);
        // the returned Picture owns its own native ref and survives the close.
        recorder.finishRecordingAsPicture()
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
            surface.canvas.drawPicture(picture)
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
 * render thread. Produced by overlay/popup surfaces ([TaoPopupSceneLayer],
 * `NativeViewOverlayController`) and collected by [TaoComposeSceneHost] during
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
)
