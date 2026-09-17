package dev.nucleusframework.window.tao.popup

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent

/**
 * Platform-agnostic surface for a standalone transparent popup panel.
 * Implemented per-platform: [TaoStandalonePopupHost] (Windows, EGL/DComp) and
 * [TaoStandalonePopupHostMac] (macOS, Metal/CAMetalLayer). Lets
 * `dev.nucleusframework.window.tao.TaoStandalonePopup` route per platform
 * without a per-platform composable.
 */
internal interface StandalonePopupHost {
    val isValid: Boolean
    val scale: Float

    var onPreviewKeyEvent: ((KeyEvent) -> Boolean)?
    var onKeyEvent: ((KeyEvent) -> Boolean)?

    fun setContent(content: @Composable () -> Unit)

    /**
     * Provides the panel's own plumbing around [content] — currently the
     * `TextureView` texture host, which must point at *this* panel's Skia
     * context.
     *
     * Called by `TaoStandalonePopup` as the **innermost** provider, after the
     * caller's replayed composition locals. That ordering is the whole point: a
     * panel composed inside a `DecoratedWindow` replays that window's locals into
     * its scene, so a provider placed around them would be shadowed by the
     * *window's* texture host and every `TextureView` in the panel would import
     * onto a context that never paints it — a silently empty box.
     */
    @Composable
    fun ProvidePanelLocals(content: @Composable () -> Unit)

    /** Logical (dp) screen position and size of the panel. */
    fun setFrame(
        xDp: Float,
        yDp: Float,
        widthDp: Float,
        heightDp: Float,
    )

    fun setVisible(visible: Boolean)

    fun setFocusable(focusable: Boolean)

    fun setOutsideClickListener(listener: (() -> Unit)?)

    fun dispose()
}
