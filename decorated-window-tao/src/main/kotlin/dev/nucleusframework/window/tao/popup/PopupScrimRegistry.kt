package dev.nucleusframework.window.tao.popup

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect

/**
 * The dialog scrims of a host window's native popup layers, in stacking order.
 *
 * A Compose `Dialog` never paints its own scrim: `Dialog.skiko.kt` writes
 * `ComposeSceneLayer.scrimColor` and leaves the painting to whoever renders
 * *underneath* the layer. Compose Desktop's `ComposeContainer.onRenderOverlay`
 * paints every layer's scrim over the main window after the main scene, and
 * `WindowComposeSceneLayer` paints the scrims of the layers above it into its
 * own window, so a popup open under a dialog is dimmed too. With native popup
 * layers each layer is a separate OS surface, so the same two passes are
 * needed here: [paintAll] from the owner window's scene, [paintAbove] from
 * each layer's scene.
 *
 * Registration order is stacking order: Compose creates layers bottom-up, and
 * a layer registers itself in its constructor.
 *
 * Threading: main / event-loop thread only, like the layers themselves. Colors
 * are read through a provider at paint time so a scrim set after registration
 * (which is always: `scrimColor` is written during the dialog's composition)
 * is picked up without re-registering.
 */
internal class PopupScrimRegistry(
    /**
     * Invoked when a layer's scrim changed. The host repaints the owner window
     * — and marks its scene visually dirty: a scrim fade alone raises no layout
     * or draw invalidation in that scene, and a host that skips presenting
     * clean frames would otherwise never show it.
     */
    private val onChanged: () -> Unit,
) {
    private val scrims = LinkedHashMap<Any, () -> Color?>()

    /** A layer's `scrimColor` changed; see [onChanged]. */
    fun notifyChanged() = onChanged()

    /** Adds [token]'s layer on top of the stack. Re-registering moves it to the top. */
    fun register(
        token: Any,
        color: () -> Color?,
    ) {
        scrims.remove(token)
        scrims[token] = color
    }

    /**
     * Drops [token]'s layer. A layer that was still dimming when it went away
     * changed the scrim stack, so this reports it like any other change: nobody
     * below observes the registry, and a host that skips clean frames would
     * otherwise leave the owner window dark until an unrelated invalidation.
     */
    fun unregister(token: Any) {
        val dimmed = scrims.remove(token)?.invoke() != null
        if (dimmed) onChanged()
    }

    /** The scrims of every registered layer, bottom-up. */
    fun all(): List<Color> = scrims.values.mapNotNull { it() }

    /** The scrims of the layers stacked above [token], bottom-up. */
    fun above(token: Any): List<Color> {
        val out = ArrayList<Color>()
        var seen = false
        for ((key, color) in scrims) {
            if (seen) color()?.let(out::add)
            if (key == token) seen = true
        }
        return out
    }

    /**
     * Paints every scrim over [rect] — the owner window's whole surface.
     * [transparent] selects the blend mode exactly as Compose's
     * `getDialogScrimBlendMode` does: a per-pixel-alpha window must only darken
     * what it drew (`SrcAtop`), an opaque one darkens everything (`SrcOver`).
     */
    fun paintAll(
        canvas: Canvas,
        rect: Rect,
        transparent: Boolean,
    ) = paint(canvas, rect, transparent, all())

    /**
     * Paints the scrims of the layers above [token] over [rect] — the visible
     * part of that layer's own surface. Popup surfaces are always per-pixel
     * transparent, so the blend is `SrcAtop`.
     */
    fun paintAbove(
        token: Any,
        canvas: Canvas,
        rect: Rect,
    ) = paint(canvas, rect, transparent = true, above(token))

    private fun paint(
        canvas: Canvas,
        rect: Rect,
        transparent: Boolean,
        colors: List<Color>,
    ) {
        if (colors.isEmpty()) return
        val paint = Paint()
        try {
            paint.blendMode = if (transparent) BlendMode.SRC_ATOP else BlendMode.SRC_OVER
            for (color in colors) {
                paint.color = color.toArgb()
                canvas.drawRect(rect, paint)
            }
        } finally {
            paint.close()
        }
    }
}
