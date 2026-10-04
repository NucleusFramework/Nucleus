package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Partial redraw (#755) without a GPU: the buffer-age bookkeeping, the EGL
 * rectangle convention, and the damage tracker's behaviour on a Compose the
 * Nucleus plugin did not patch — which is what this test classpath is, and
 * what an app run outside the plugin gets.
 *
 * The tracker on a patched Compose is covered end to end, against a full
 * render of every frame: `examples/partial-redraw-demo` with
 * `-Dnucleus.tao.partialRedraw.verify=true`.
 */
class PartialRedrawTest {
    private val a = IntRect(10, 10, 20, 20)
    private val b = IntRect(100, 50, 110, 60)

    @Test
    fun `a buffer of age 1 repaints the frame's own damage`() {
        val history = DamageHistory()
        history.push(b)
        assertEquals(a, history.repaintRegion(a, bufferAge = 1))
    }

    @Test
    fun `an older buffer also repaints what the frames since changed`() {
        val history = DamageHistory()
        history.push(b)
        history.push(IntRect.Zero)
        // Age 3: the buffer predates both frames pushed.
        assertEquals(IntRect(10, 10, 110, 60), history.repaintRegion(a, bufferAge = 3))
        // Age 2: only the newest one, which changed nothing.
        assertEquals(a, history.repaintRegion(a, bufferAge = 2))
    }

    @Test
    fun `any doubt repaints everything`() {
        val history = DamageHistory()
        history.push(a)
        assertNull(history.repaintRegion(null, bufferAge = 1), "damage unknown")
        assertNull(history.repaintRegion(a, bufferAge = 0), "buffer content undefined")
        assertNull(history.repaintRegion(a, bufferAge = -1), "no partial redraw on the surface")
        assertNull(history.repaintRegion(a, bufferAge = 3), "older than the history")
        history.push(null)
        assertNull(history.repaintRegion(a, bufferAge = 2), "a full frame in between")
        history.clear()
        assertNull(history.repaintRegion(a, bufferAge = 2), "history cleared")
        assertEquals(a, history.repaintRegion(a, bufferAge = 1))
    }

    @Test
    fun `a frame that changed nothing repaints only what the frames since changed`() {
        val history = DamageHistory()
        history.push(a)
        assertEquals(a, history.repaintRegion(IntRect.Zero, bufferAge = 2))
        assertEquals(IntRect.Zero, history.repaintRegion(IntRect.Zero, bufferAge = 1))
        // It still presents — one pixel, anywhere.
        assertEquals(IntRect(0, 0, 1, 1), IntRect.Zero.orAPixel())
    }

    @Test
    fun `EGL damage rectangles have a bottom-left origin`() {
        assertContentEquals(intArrayOf(10, 580, 10, 10), PartialRedraw.eglRects(a, surfaceHeight = 600))
    }

    @Test
    fun `an unpatched Compose reports no damage and keeps rendering`() {
        runTaoSceneTest {
            var red by mutableStateOf(true)
            setContent { Box(Modifier.size(10.dp).background(if (red) Color.Red else Color.Blue)) }
            repeat(3) {
                red = !red
                frame()
                assertNull(lastFrameDamage, "no content versions, no damage")
                val reason = assertNotNull(lastFullFrameReason)
                assertTrue("not patched" in reason, reason)
            }
        }
    }
}
