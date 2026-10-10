package dev.nucleusframework.window.tao.popup

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.WindowExceptionHandler
import dev.nucleusframework.window.tao.scene.TaoRecordedSurface
import kotlin.coroutines.CoroutineContext

/**
 * Plumbing the popup / overlay scenes need from their host scene.
 * Implemented by [TaoComposeSceneHost], consumed by [TaoPopupSceneLayer]
 * and native popup layers.
 *
 * Threading: every call must run on the macOS main thread.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal interface TaoPopupHost {
    /** NSView pointer of the host window's content view. */
    val parentNsView: Long

    /** Backing-scale factor (logical→physical multiplier). */
    val scale: Float

    /**
     * Host window's content size in **physical pixels**. Used as the
     * inner scene's initial constraints so Compose's `RootMeasurePolicy`
     * (in `Popup.skiko.kt`) measures with non-zero values — zero-sized
     * constraints make the policy short-circuit and `boundsInWindow`
     * never updates.
     */
    val parentWindowSize: IntSize

    /**
     * The owner window's live `WindowInfo`. Its `containerSize` is snapshot
     * state, so a dialog that centres itself in it (`Dialog.skiko.kt` reads
     * `LocalWindowInfo.current.containerSize`) re-measures when the window is
     * resized — [parentWindowSize] is a plain read and would leave it frozen.
     */
    val parentWindowInfo: WindowInfo

    /**
     * Visible-frame size (screen minus menu bar + dock) of the NSScreen
     * hosting the owner window, in **physical pixels**. Used by popup
     * layers as the upper bound for inner-scene layout — popups can
     * legitimately extend beyond the owner window up to the screen edge,
     * so the owner's own size is a false ceiling (a 400×300 owner on a
     * 4K display would otherwise force a tall DropdownMenu to scroll
     * internally instead of laying out full-height + flipping at the
     * screen edge).
     *
     * Falls back to [parentWindowSize] when the NSScreen is not yet
     * resolvable (e.g. very early init before the NSView is attached
     * to a window). Read once at popup construction; not reactive — the
     * popup is torn down and rebuilt on owner-move/screen-change anyway.
     */
    val workAreaSize: IntSize get() = parentWindowSize

    /**
     * Where the owner window sits on screen, and where the displays' work
     * areas are — the origin [workAreaSize] deliberately throws away.
     *
     * [workAreaSize] gives the popup room to lay out at full size, but Compose
     * then flips and clips inside that size *rooted at the window*, so the
     * decision is made against a virtual screen rather than the real one.
     * Layers use this to clamp their native frame back into the display's work
     * area at the point they push it. `null` when the platform cannot resolve
     * it (early init, no screen), which restores the unclamped behaviour.
     *
     * Read on every frame push; implementations must stay cheap.
     */
    val popupScreenGeometry: PopupScreenGeometry? get() = null

    /** Coroutine context to feed inner scenes (parent context + frame clock + flushing dispatcher). */
    val sceneCoroutineContext: CoroutineContext

    /**
     * The owner window's exception handler, so a popup scene reports failures
     * through the same channel as the window it belongs to. Mirrors Compose
     * Desktop's `WindowComposeSceneLayer`, which forwards
     * `composeContainer.exceptionHandler` into its own mediator.
     */
    val exceptionHandler: WindowExceptionHandler? get() = null

    /**
     * Offset added to a popup's `boundsInWindow` before positioning the
     * NSPanel in the host NSWindow. Non-zero when the popup originates
     * from a nested scene (e.g. `NativeView`'s overlay) whose origin is
     * not at the host window's top-left. Without it, popup framework
     * coordinates from the nested scene would be interpreted as host-
     * window coords and end up at the wrong place.
     */
    val coordinateOffset: IntOffset get() = IntOffset.Zero

    /**
     * Whether the owner window was created per-pixel transparent
     * (`DecoratedWindow(transparent = true)`, #416). Overlay scenes render
     * inside the owner's surface, so they forward this as
     * `PlatformContext.isWindowTransparent` — the hint Compose uses to pick
     * the alpha-aware dialog-scrim blend mode (#559).
     */
    val isOwnerWindowTransparent: Boolean get() = false

    /**
     * The dialog scrims of this host's layers. A layer registers its
     * `scrimColor` here for its whole lifetime; the host paints them all over
     * the owner window's scene, and every layer paints the ones above it into
     * its own surface — see [PopupScrimRegistry].
     */
    val popupScrims: PopupScrimRegistry

    fun requestRedraw()

    /**
     * Registers a per-frame recorder. The host invokes [record] on the **main
     * thread** during its record pass; it must drive the overlay/popup scene
     * into a [TaoRecordedSurface] (via [recordSceneToPicture]) or return `null`
     * to skip the frame (zero-size / disposed). The host then replays the
     * returned surface on its render thread after the main scene.
     */
    fun registerRenderer(
        token: Any,
        record: () -> TaoRecordedSurface?,
    )

    fun unregisterRenderer(token: Any)

    /**
     * A layer this host handed out has closed and must leave the host's live
     * set. Compose closes a native popup layer only when the layer's own
     * disappearance animation finishes; an owner window torn down before
     * that would otherwise leave the layer's window mapped for good, so the
     * host tracks its layers and closes the survivors on detach.
     */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun onLayerClosed(layer: androidx.compose.ui.scene.ComposeSceneLayer) {}

    /**
     * Runs [block] on the host's dedicated Metal render thread and blocks until
     * it returns. Overlay/popup surfaces must create, use, and close their Skia
     * `DirectContext` here so Skia's Metal context thread-affinity is respected
     * (the host's per-frame replay also runs on this thread).
     *
     * Safe to call (blocking) from the main thread during scene composition /
     * disposal — at those points the render thread is idle (frames are
     * serialized and Compose disposal runs inside the main-thread record pass),
     * so it never deadlocks against an in-flight replay.
     */
    fun <T> runOnRenderThread(block: () -> T): T

    /**
     * Registers a key handler called from `TaoComposeSceneHost.onKeyEvent`
     * before the main scene's key dispatch. Returning `true` consumes
     * the event. Tao's macOS pipeline intercepts keys before they reach
     * AppKit's responder chain, so overlays can't receive `keyDown:`
     * natively and must piggy-back on this forwarding path.
     */
    fun registerKeyHandler(
        token: Any,
        handler: (KeyEvent) -> Boolean,
    )

    fun unregisterKeyHandler(token: Any)

    /**
     * Forwards a [androidx.compose.ui.input.pointer.PointerIcon] change
     * from an overlay scene to the host window's cursor. [iconCode] is
     * one of the `TaoCursorIcon` constants.
     */
    fun setCursor(iconCode: Int)
}

internal val LocalTaoPopupHost: ProvidableCompositionLocal<TaoPopupHost?> =
    compositionLocalOf { null }
