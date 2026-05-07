package io.github.kdroidfilter.nucleus.window.tao.render

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.coroutines.CoroutineContext
import org.jetbrains.skia.DirectContext

/**
 * Windows counterpart to [TaoPopupHost]. Plumbing the overlay scene
 * (and, in Phase 6+, popup scenes) need from their host scene on
 * Windows.
 *
 * macOS keys on `parentNsView`; Windows keys on `parentHwnd`. Otherwise
 * the surface is identical so the controller can be ported line-for-line.
 *
 * Threading: every call must run on the host HWND's UI thread.
 */
internal interface TaoPopupHostWindows {
    /** HWND of the host (Tao main) window. */
    val parentHwnd: Long

    /** Backing-scale factor (logical→physical multiplier). */
    val scale: Float

    /** Host window's content size in physical pixels. */
    val parentWindowSize: IntSize

    /** Coroutine context to feed inner scenes. */
    val sceneCoroutineContext: CoroutineContext

    /**
     * Offset added to a popup's `boundsInWindow` before positioning the
     * popup HWND in screen coords. Non-zero when the popup originates
     * from a nested scene whose origin is not at the host window's
     * top-left.
     */
    val coordinateOffset: IntOffset get() = IntOffset.Zero

    /**
     * The HOST scene's Skia DirectContext — shared with every
     * overlay/popup on Windows. Single-HGLRC architecture: the native
     * side gives every overlay/popup HWND the host's HGLRC (via
     * `wglMakeCurrent(overlayDC, hostHGLRC)`), so all surfaces draw
     * through one Skia context. resetGLAll() between draws handles the
     * default-framebuffer state delta when wglMakeCurrent swaps HDCs.
     */
    val hostDirectContext: DirectContext

    fun requestRedraw()

    fun registerRenderer(token: Any, render: () -> Unit)
    fun unregisterRenderer(token: Any)

    /**
     * Registers a key handler called from `TaoComposeSceneHostWindows.onKeyEvent`
     * before the main scene's key dispatch. Returning `true` consumes
     * the event. Wired by Phase 8.
     */
    fun registerKeyHandler(token: Any, handler: (KeyEvent) -> Boolean)
    fun unregisterKeyHandler(token: Any)
}

internal val LocalTaoPopupHostWindows: ProvidableCompositionLocal<TaoPopupHostWindows?> =
    compositionLocalOf { null }
