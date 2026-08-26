package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * Compose 1.12's RectManager throws `LayoutNode not found in RectList` when a
 * large placed subtree is detached and a new one is placed in the same frame
 * (nucleus-demo tab switches). These cases drive that remount through the
 * production [TaoSceneBundle.render] path.
 */
class TaoSceneContentSwapTest {
    @Test
    fun `swapping a scrollable page of buttons does not crash RectList`() =
        runTaoSceneTest(width = 800, height = 480) {
            var tab by mutableStateOf(0)
            setContent {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(32.dp)) {
                        Box(Modifier.clickable { tab = 0 }.padding(8.dp)) { Text("A") }
                        Box(Modifier.clickable { tab = 1 }.padding(8.dp)) { Text("B") }
                    }
                    Box(Modifier.fillMaxSize()) {
                        when (tab) {
                            0 -> ScrollableButtonPage("one")
                            else -> ScrollableButtonPage("two")
                        }
                    }
                }
            }
            tab = 1
            frame()
            tab = 0
            frame()
            tab = 1
            frameUntilIdle()
        }

    @Test
    fun `clicking a tab remounts the body without a RectList crash`() =
        runTaoSceneTest(width = 800, height = 480) {
            var tab by mutableStateOf(0)
            setContent {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(32.dp)) {
                        Box(Modifier.clickable { tab = 0 }.padding(8.dp)) { Text("A") }
                        Box(Modifier.clickable { tab = 1 }.padding(8.dp)) { Text("B") }
                    }
                    Box(Modifier.fillMaxSize()) {
                        when (tab) {
                            0 -> ScrollableButtonPage("one")
                            else -> ScrollableButtonPage("two")
                        }
                    }
                }
            }
            // Second tab label is to the right of the first.
            click(x = 40f, y = 16f)
            frameUntilIdle()
            click(x = 8f, y = 16f)
            frameUntilIdle()
        }
}

@Composable
private fun ScrollableButtonPage(label: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        repeat(24) { index ->
            Button(
                onClick = {},
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .graphicsLayer { alpha = 1f },
            ) {
                Text("$label-$index")
            }
        }
    }
}
