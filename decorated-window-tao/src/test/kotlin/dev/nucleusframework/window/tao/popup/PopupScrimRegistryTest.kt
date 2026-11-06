package dev.nucleusframework.window.tao.popup

import androidx.compose.ui.graphics.Color
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit cases for the dialog-scrim bookkeeping of native popup layers: which
 * scrims each surface paints, and how they blend.
 */
class PopupScrimRegistryTest {
    private val bottom = Any()
    private val middle = Any()
    private val top = Any()

    private fun stack(vararg colors: Pair<Any, Color?>): PopupScrimRegistry =
        PopupScrimRegistry(onChanged = {}).apply {
            for ((token, color) in colors) register(token) { color }
        }

    // ── Bookkeeping ────────────────────────────────────────────────────────

    @Test
    fun `popups without a scrim contribute nothing`() {
        val registry = stack(bottom to null, middle to null)
        assertEquals(emptyList(), registry.all())
        assertEquals(emptyList(), registry.above(bottom))
    }

    @Test
    fun `the owner window paints every scrim bottom-up`() {
        val registry = stack(bottom to null, middle to Color.Red, top to Color.Blue)
        assertEquals(listOf(Color.Red, Color.Blue), registry.all())
    }

    @Test
    fun `a layer paints only the scrims of the layers above it`() {
        val registry = stack(bottom to Color.Red, middle to Color.Green, top to Color.Blue)
        assertEquals(listOf(Color.Green, Color.Blue), registry.above(bottom))
        assertEquals(listOf(Color.Blue), registry.above(middle))
        assertEquals(emptyList(), registry.above(top))
    }

    @Test
    fun `an unknown layer sees no scrim above it`() {
        val registry = stack(bottom to Color.Red)
        assertEquals(emptyList(), registry.above(Any()))
    }

    @Test
    fun `a scrim written after registration is read at paint time`() {
        var color: Color? = null
        val registry = PopupScrimRegistry(onChanged = {}).apply { register(top) { color } }
        assertEquals(emptyList(), registry.all())
        color = Color.Black
        assertEquals(listOf(Color.Black), registry.all())
    }

    @Test
    fun `a scrim change is reported to the host`() {
        var changes = 0
        val registry = PopupScrimRegistry(onChanged = { changes++ })
        registry.notifyChanged()
        assertEquals(1, changes)
    }

    @Test
    fun `unregistering removes the layer from every view`() {
        val registry = stack(bottom to Color.Red, top to Color.Blue)
        registry.unregister(top)
        assertEquals(listOf(Color.Red), registry.all())
        assertEquals(emptyList(), registry.above(bottom))
    }

    /**
     * A layer torn down while its scrim was still opaque — a dialog removed
     * from composition rather than faded out by `DialogAppearanceController` —
     * takes its dimming with it, and nothing under it observes that. Without a
     * repaint the owner window stays dark until an unrelated invalidation
     * happens to produce a non-clean frame.
     */
    @Test
    fun `unregistering a dimming layer repaints the host`() {
        var changes = 0
        val registry = PopupScrimRegistry(onChanged = { changes++ })
        registry.register(top) { Color.Black }
        registry.unregister(top)
        assertEquals(1, changes)
    }

    /** The common case — a plain popup — must not cost a repaint on the way out. */
    @Test
    fun `unregistering a layer that dimmed nothing is silent`() {
        var changes = 0
        val registry = PopupScrimRegistry(onChanged = { changes++ })
        registry.register(top) { null }
        registry.unregister(top)
        registry.unregister(Any())
        assertEquals(0, changes)
    }

    @Test
    fun `re-registering moves a layer to the top of the stack`() {
        val registry = stack(bottom to Color.Red, top to Color.Blue)
        registry.register(bottom) { Color.Red }
        assertEquals(listOf(Color.Blue, Color.Red), registry.all())
        assertEquals(listOf(Color.Red), registry.above(top))
    }

    // ── Painting ───────────────────────────────────────────────────────────

    private fun paintOnto(
        opaqueLeftHalf: Boolean,
        paint: (Canvas) -> Unit,
    ): Bitmap {
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(4, 2, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
        Canvas(bitmap).use { canvas ->
            canvas.clear(0x00000000)
            if (opaqueLeftHalf) {
                val white = Paint().apply { color = 0xFFFFFFFF.toInt() }
                canvas.drawRect(Rect.makeWH(2f, 2f), white)
            }
            paint(canvas)
        }
        return bitmap
    }

    private fun alphaAt(
        bitmap: Bitmap,
        x: Int,
        y: Int,
    ): Int = (bitmap.getColor(x, y) ushr 24) and 0xFF

    @Test
    fun `an opaque owner window is dimmed everywhere`() {
        val registry = stack(top to Color(0x80000000))
        val bitmap =
            paintOnto(opaqueLeftHalf = true) {
                registry.paintAll(it, Rect.makeWH(4f, 2f), transparent = false)
            }
        assertTrue(alphaAt(bitmap, 0, 0) == 0xFF, "drawn pixels stay opaque")
        assertTrue(alphaAt(bitmap, 3, 1) > 0, "the scrim lands on undrawn pixels of an opaque window")
    }

    @Test
    fun `a per-pixel-transparent surface is dimmed only where it drew`() {
        val registry = stack(bottom to null, top to Color(0x80000000))
        val bitmap =
            paintOnto(opaqueLeftHalf = true) {
                registry.paintAbove(bottom, it, Rect.makeWH(4f, 2f))
            }
        assertTrue(alphaAt(bitmap, 0, 0) == 0xFF, "drawn pixels stay opaque")
        assertEquals(0, alphaAt(bitmap, 3, 1), "SrcAtop leaves undrawn pixels transparent")
        assertTrue((bitmap.getColor(0, 0) and 0xFF) < 0xFF, "drawn pixels are darkened")
    }

    @Test
    fun `no scrim leaves the surface untouched`() {
        val registry = stack(bottom to null)
        val bitmap =
            paintOnto(opaqueLeftHalf = false) {
                registry.paintAll(it, Rect.makeWH(4f, 2f), transparent = false)
            }
        assertEquals(0, alphaAt(bitmap, 0, 0))
    }
}
