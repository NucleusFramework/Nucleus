package dev.nucleusframework.window.tao.workspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.window.tao.TaoWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Screen placement of a published drop target and the registry that keeps one per window. */
class HostGeometryTest {
    private val a = TaoWindow(handle = 1L)
    private val b = TaoWindow(handle = 2L)

    @Test
    fun `client origin splits the side borders evenly and matches them at the bottom`() {
        // A 820×660 frame around 800×600 of content: 10 px borders left and
        // right, 10 px assumed below, the remaining 50 px title bar and top
        // border.
        val origin = clientOriginPx(longArrayOf(100L, 200L, 820L, 660L), IntSize(800, 600))

        assertEquals(Offset(110f, 250f), origin)
        // A plain resize frame — Win32's invisible borders — adds the same
        // 8 px on every side, so the content starts 8 px in on both axes.
        assertEquals(Offset(108f, 208f), clientOriginPx(longArrayOf(100L, 200L, 816L, 616L), IntSize(800, 600)))
        // Client-side decorated: frame == content, origin == frame origin.
        assertEquals(Offset(100f, 200f), clientOriginPx(longArrayOf(100L, 200L, 800L, 600L), IntSize(800, 600)))
    }

    @Test
    fun `screen rect is unknown until both the container size and the outer frame are`() {
        var outer: LongArray? = null
        val geometry = HostGeometry(a, outerBoundsPx = { outer }, scaleFactor = { 1f })
        geometry.layoutBoundsInWindowPx = Rect(0f, 40f, 800f, 600f)

        assertNull(geometry.clientOriginPx(), "no container size yet")
        geometry.containerSizePx = IntSize(800, 600)
        assertNull(geometry.layoutScreenRectPx(), "unmapped window has no frame")

        outer = longArrayOf(100L, 100L, 800L, 600L)
        assertEquals(Rect(100f, 140f, 900f, 700f), geometry.layoutScreenRectPx())
    }

    @Test
    fun `scale falls back to one while the window reports none`() {
        val geometry = HostGeometry(a, scaleFactor = { 0f })
        assertEquals(1f, geometry.scaleOrOne())
        assertEquals(2f, HostGeometry(a, scaleFactor = { 2f }).scaleOrOne())
    }

    @Test
    fun `the registry keeps one geometry per window and only that one can unregister`() {
        val registry = HostGeometryRegistry()
        val first = HostGeometry(a)
        val second = HostGeometry(a)
        registry.register(first)
        registry.register(second)
        assertSame(second, registry[a], "the latest publisher wins")

        // The layout that was replaced disposes later: it must not take the
        // live one down with it.
        registry.unregister(first)
        assertSame(second, registry[a])
        registry.unregister(second)
        assertNull(registry[a])
        assertNull(registry[null])
    }

    @Test
    fun `ordered lists the given hosts first and the rest in registration order`() {
        val registry = HostGeometryRegistry()
        val geometryA = HostGeometry(a)
        val geometryB = HostGeometry(b)
        registry.register(geometryA)
        registry.register(geometryB)

        assertEquals(listOf(geometryB, geometryA), registry.ordered(listOf(b, a)))
        assertEquals(listOf(geometryB, geometryA), registry.ordered(listOf(b)), "unnamed hosts follow")
        assertEquals(listOf(geometryA, geometryB), registry.ordered(emptyList()))
        // A host without a geometry (no layout composed) is simply skipped.
        assertEquals(listOf(geometryA, geometryB), registry.ordered(listOf(TaoWindow(handle = 9L), a)))
    }
}
