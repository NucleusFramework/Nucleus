package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A popup composed by a subcomposition (`BoxWithConstraints`, `SubcomposeLayout`) leaves it during the main owner's
 * layout. Compose measures the owners from a copy of its list taken before that layout, so the popup's owner, already
 * disposed, was measured next: "RootNodeOwner is already disposed" (a tab strip's overflow menu, open while the window
 * grows past the overflow).
 */
@OptIn(InternalComposeUiApi::class)
class TaoScenePopupDisposedInLayoutTest {
    @Test
    fun `a popup its subcomposition drops during layout does not break the frame`() =
        runTaoSceneTest(width = 100, height = 100) {
            setContent {
                BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)) {
                    if (maxWidth < 150.dp) {
                        Popup(offset = IntOffset(20, 20)) {
                            Box(Modifier.size(30.dp).background(Color.Blue))
                        }
                    }
                }
            }
            frame()
            assertEquals(BLUE, pixelAt(30, 30))
            scene.size = IntSize(200, 100)
            frame()
            assertEquals(WHITE, pixelAt(30, 30))
        }

    private companion object {
        const val BLUE = 0xFF0000FF.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
