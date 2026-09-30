@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.nucleusframework.window.tao

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.nucleusframework.window.tao.scene.runTaoSceneTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [NativePopupLayers] on a real `CanvasLayersComposeScene` — the scene a
 * window without `nativePopupLayers` runs on. The window's factory is a
 * recording fake: what matters here is *which* pipeline a `Popup` ends up in,
 * not what the native layer draws.
 */
class NativePopupLayersTest {
    @Test
    fun `a Popup inside NativePopupLayers is built by the window's native layer factory`() {
        val factory = RecordingLayerFactory()
        runTaoSceneTest(width = 100, height = 100) {
            setContent {
                CompositionLocalProvider(LocalTaoNativePopupLayerFactory provides factory::create) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        NativePopupLayers {
                            Popup(offset = IntOffset(20, 20), properties = PopupProperties(focusable = true)) {
                                Box(Modifier.size(30.dp).background(Color.Blue))
                            }
                        }
                    }
                }
            }
            frame()
            val layer = factory.layers.single()
            assertTrue(layer.contentSet, "Popup content must be handed to the native layer")
            assertTrue(layer.focusable, "the Popup's properties must reach the native layer")
            // The in-scene pipeline was bypassed: nothing paints the popup here.
            assertEquals(WHITE, pixelAt(30, 30))
        }
    }

    @Test
    fun `a Popup outside NativePopupLayers keeps drawing in the scene`() {
        val factory = RecordingLayerFactory()
        runTaoSceneTest(width = 100, height = 100) {
            setContent {
                CompositionLocalProvider(LocalTaoNativePopupLayerFactory provides factory::create) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        NativePopupLayers { }
                        Popup(offset = IntOffset(20, 20)) {
                            Box(Modifier.size(30.dp).background(Color.Blue))
                        }
                    }
                }
            }
            frame()
            assertTrue(factory.layers.isEmpty(), "the opt-in must not leak out of its subtree")
            assertEquals(BLUE, pixelAt(30, 30))
        }
    }

    @Test
    fun `without a native layer factory NativePopupLayers is a no-op`() {
        runTaoSceneTest(width = 100, height = 100) {
            setContent {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    NativePopupLayers {
                        Popup(offset = IntOffset(20, 20)) {
                            Box(Modifier.size(30.dp).background(Color.Blue))
                        }
                    }
                }
            }
            frame()
            assertEquals(BLUE, pixelAt(30, 30))
        }
    }

    @Test
    fun `closing the Popup closes the native layer`() {
        val factory = RecordingLayerFactory()
        runTaoSceneTest(width = 100, height = 100) {
            setContent {
                CompositionLocalProvider(LocalTaoNativePopupLayerFactory provides factory::create) {
                    NativePopupLayers {
                        Popup { Box(Modifier.size(30.dp)) }
                    }
                }
            }
            frame()
            assertFalse(factory.layers.single().closed)
            setContent { }
            frame()
            assertTrue(factory.layers.single().closed)
        }
    }
}

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLUE = 0xFF0000FF.toInt()

private class RecordingLayerFactory {
    val layers = mutableListOf<RecordingLayer>()

    fun create(
        density: Density,
        layoutDirection: LayoutDirection,
        focusable: Boolean,
        consumePointerInputOutside: Boolean,
    ): ComposeSceneLayer =
        RecordingLayer(density, layoutDirection, focusable, consumePointerInputOutside).also { layers += it }
}

/** A [ComposeSceneLayer] that records what Compose asks of it and composes nothing. */
private class RecordingLayer(
    override var density: Density,
    override var layoutDirection: LayoutDirection,
    override var focusable: Boolean,
    override var consumePointerInputOutside: Boolean,
) : ComposeSceneLayer {
    override var boundsInWindow: IntRect = IntRect.Zero
    override var compositionLocalContext: CompositionLocalContext? = null
    override var scrimColor: Color? = null
    var contentSet = false
    var closed = false

    override fun close() {
        closed = true
    }

    override fun setContent(
        parentCompositionContext: CompositionContext,
        content: @Composable () -> Unit,
    ) {
        contentSet = true
    }

    override fun setKeyEventListener(
        onPreviewKeyEvent: ((KeyEvent) -> Boolean)?,
        onKeyEvent: ((KeyEvent) -> Boolean)?,
    ) = Unit

    override fun setOutsidePointerEventListener(
        onOutsidePointerEvent: ((eventType: PointerEventType, button: PointerButton?) -> Unit)?,
    ) = Unit

    override fun calculateLocalPosition(positionInWindow: IntOffset): IntOffset =
        positionInWindow - boundsInWindow.topLeft
}
