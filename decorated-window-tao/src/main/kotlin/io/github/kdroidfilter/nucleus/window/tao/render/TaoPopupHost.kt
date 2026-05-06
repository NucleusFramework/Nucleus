package io.github.kdroidfilter.nucleus.window.tao.render

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.IntSize
import kotlin.coroutines.CoroutineContext

/**
 * Plumbing the popup / overlay scenes need from their host scene.
 * Implemented by [TaoComposeSceneHost], consumed by [TaoPopupSceneLayer]
 * and `NativeViewOverlayController`.
 *
 * Threading: every call must run on the macOS main thread.
 */
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

    /** Coroutine context to feed inner scenes (parent context + frame clock + flushing dispatcher). */
    val sceneCoroutineContext: CoroutineContext

    fun requestRedraw()

    fun registerRenderer(token: Any, render: () -> Unit)
    fun unregisterRenderer(token: Any)

    /**
     * Registers a key handler called from `TaoComposeSceneHost.onKeyEvent`
     * before the main scene's key dispatch. Returning `true` consumes
     * the event. Tao's macOS pipeline intercepts keys before they reach
     * AppKit's responder chain, so overlays can't receive `keyDown:`
     * natively and must piggy-back on this forwarding path.
     */
    fun registerKeyHandler(token: Any, handler: (KeyEvent) -> Boolean)
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
