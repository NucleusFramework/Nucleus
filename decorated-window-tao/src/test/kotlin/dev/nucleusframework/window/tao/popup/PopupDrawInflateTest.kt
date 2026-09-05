package dev.nucleusframework.window.tao.popup

import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit cases for the draw margin of native popup layers: the surface must
 * cover what Compose draws around the layout bounds, on every side.
 */
class PopupDrawInflateTest {
    private val bounds = IntRect(100, 200, 300, 400)

    @Test
    fun `the margin is 32 dp in physical pixels`() {
        assertEquals(32, popupDrawMarginPx(1f))
        assertEquals(64, popupDrawMarginPx(2f))
        assertEquals(40, popupDrawMarginPx(1.25f))
    }

    @Test
    fun `a fractional margin rounds up`() {
        assertEquals(48, popupDrawMarginPx(1.5f))
        assertEquals(36, popupDrawMarginPx(1.1f))
    }

    @Test
    fun `a density below one is treated as one`() {
        assertEquals(32, popupDrawMarginPx(0.5f))
    }

    @Test
    fun `the surface is inflated on every side`() {
        assertEquals(IntRect(68, 168, 332, 432), popupDrawBounds(bounds, 1f))
        assertEquals(IntRect(36, 136, 364, 464), popupDrawBounds(bounds, 2f))
    }

    @Test
    fun `the content keeps its size and offset inside the surface`() {
        val draw = popupDrawBounds(bounds, 2f)
        assertEquals(bounds.size.width + 2 * 64, draw.size.width)
        assertEquals(64, bounds.left - draw.left)
        assertEquals(64, bounds.top - draw.top)
    }
}
