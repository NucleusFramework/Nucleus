package dev.nucleusframework.window.tao.popup

import androidx.compose.ui.unit.IntRect
import kotlin.math.ceil

/**
 * Margin, in dp, that a native popup layer's surface extends past
 * `boundsInWindow` on every side, so that what Compose draws outside the
 * layout rectangle is not clipped at the surface edge.
 *
 * `boundsInWindow` is the popup's *layout* rectangle. What Compose draws is
 * routinely larger: a Material dialog or menu carries an elevation shadow
 * (6 dp for an `AlertDialog`, 8 dp for a `DropdownMenu`, whose blur and
 * offset reach roughly twice that), and `Dialog.skiko.kt` animates the dialog
 * in from 10 dp below, scaled down and faded. An in-scene layer overflows into
 * the window canvas for free; a separate OS surface clips at its own edge.
 *
 * The margin is a constant rather than a measurement. Compose Desktop's
 * `WindowComposeSceneLayer` measures the drawn bounds with a picture
 * recorder's R-tree, but since Compose 1.12 a scene draws through skiko
 * `RenderNode`s — a single `drawDrawable` op whose bounds are unbounded — so
 * that measurement only ever reports the whole canvas. 32 dp covers every
 * Material elevation and the appearance animation with room to spare, and
 * costs a constant fraction of the surface.
 */
internal const val POPUP_DRAW_MARGIN_DP: Float = 32f

/** [POPUP_DRAW_MARGIN_DP] in physical pixels at [density] (px per dp). */
internal fun popupDrawMarginPx(density: Float): Int = ceil(POPUP_DRAW_MARGIN_DP * density.coerceAtLeast(1f)).toInt()

/** [bounds] inflated by [popupDrawMarginPx]: the rectangle the layer's surface must cover. */
internal fun popupDrawBounds(
    bounds: IntRect,
    density: Float,
): IntRect {
    val margin = popupDrawMarginPx(density)
    return IntRect(
        left = bounds.left - margin,
        top = bounds.top - margin,
        right = bounds.right + margin,
        bottom = bounds.bottom + margin,
    )
}
