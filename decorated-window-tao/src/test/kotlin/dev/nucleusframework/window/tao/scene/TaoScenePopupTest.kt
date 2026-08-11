package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Stage-1 popup tests: Compose Popups in the CanvasLayers scene (the same
 * scene type the tao main window host uses, where popup content stays in the
 * window's render target) render, layer above content, and dismiss.
 */
class TaoScenePopupTest {
    @Test
    fun `popup renders above the window content`() =
        runTaoSceneTest(width = 100, height = 100) {
            setContent {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Popup(offset = IntOffset(20, 20)) {
                        Box(Modifier.size(30.dp).background(Color.Blue))
                    }
                }
            }
            frame()
            assertEquals(BLUE, pixelAt(30, 30))
            assertEquals(WHITE, pixelAt(70, 70))
        }

    @Test
    fun `popup disappears when its state is cleared`() =
        runTaoSceneTest(width = 100, height = 100) {
            var show by mutableStateOf(true)
            setContent {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    if (show) {
                        Popup(offset = IntOffset(20, 20)) {
                            Box(Modifier.size(30.dp).background(Color.Blue))
                        }
                    }
                }
            }
            frame()
            assertEquals(BLUE, pixelAt(30, 30))
            show = false
            frame()
            assertEquals(WHITE, pixelAt(30, 30))
        }

    @Test
    fun `outside click dismisses a focusable popup`() =
        runTaoSceneTest(width = 200, height = 200) {
            var dismissed = false
            setContent {
                var open by remember { mutableStateOf(true) }
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    if (open) {
                        Popup(
                            offset = IntOffset(20, 20),
                            onDismissRequest = {
                                open = false
                                dismissed = true
                            },
                            // focusable popup: outside clicks request dismissal
                            properties =
                                androidx.compose.ui.window
                                    .PopupProperties(focusable = true),
                        ) {
                            Box(Modifier.size(40.dp).background(Color.Blue))
                        }
                    }
                }
            }
            frame()
            assertEquals(BLUE, pixelAt(40, 40))
            click(150f, 150f) // outside the popup bounds
            frame()
            assertEquals(true, dismissed, "outside click must request dismissal")
            assertEquals(WHITE, pixelAt(40, 40))
        }

    @Test
    fun `click inside a focusable popup does not dismiss it`() =
        runTaoSceneTest(width = 200, height = 200) {
            var dismissed = false
            setContent {
                var open by remember { mutableStateOf(true) }
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    if (open) {
                        Popup(
                            offset = IntOffset(20, 20),
                            onDismissRequest = {
                                open = false
                                dismissed = true
                            },
                            properties =
                                androidx.compose.ui.window
                                    .PopupProperties(focusable = true),
                        ) {
                            Box(Modifier.size(40.dp).background(Color.Blue))
                        }
                    }
                }
            }
            frame()
            click(40f, 40f) // inside
            frame()
            assertEquals(false, dismissed)
            assertEquals(BLUE, pixelAt(40, 40))
        }

    /**
     * #502: a popup surface whose physical size is not a multiple of the
     * announced Wayland `buffer_scale` is a fatal protocol error, so
     * [alignToBufferScale] must round every dimension UP — and never to zero,
     * which is what the popup bootstrap (an unmeasured, 0-sized layer) feeds it.
     */
    @Test
    fun `buffer scale alignment rounds up and never collapses to zero`() {
        // Scale 1: identity, except that a surface always has at least one pixel.
        assertEquals(1, alignToBufferScale(0, 1))
        assertEquals(1, alignToBufferScale(1, 1))
        assertEquals(101, alignToBufferScale(101, 1))
        // Scale 2: the bootstrap 0/1 px popup and the odd sizes that crashed.
        assertEquals(2, alignToBufferScale(0, 2))
        assertEquals(2, alignToBufferScale(1, 2))
        assertEquals(2, alignToBufferScale(2, 2))
        assertEquals(102, alignToBufferScale(101, 2))
        assertEquals(62, alignToBufferScale(61, 2))
        assertEquals(800, alignToBufferScale(800, 2))
        // Scale 3: alignment costs at most scale - 1 px.
        assertEquals(3, alignToBufferScale(1, 3))
        assertEquals(102, alignToBufferScale(100, 3))
        assertEquals(102, alignToBufferScale(101, 3))
        assertEquals(102, alignToBufferScale(102, 3))
        // Degenerate scales are clamped, not divided by.
        assertEquals(1, alignToBufferScale(0, 0))
        assertEquals(7, alignToBufferScale(7, -1))
    }

    private companion object {
        const val BLUE = 0xFF0000FF.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
