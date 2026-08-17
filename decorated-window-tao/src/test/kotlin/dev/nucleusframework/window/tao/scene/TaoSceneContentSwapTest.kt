package dev.nucleusframework.window.tao.scene

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * Guards the Compose 1.12 RectList remount crash on the production
 * [TaoSceneBundle] path. See that class KDoc: AWT paint order plus a root
 * remasure — not an app-level `Box` around the tab body.
 *
 * Cases cover state-driven remount, click remount, `graphicsLayer` siblings,
 * Press-inside-`sendPointerEvent`, and Press+Release *without* an intervening
 * frame (Tao delivers Move/Release before the next paint; AWT usually does
 * not).
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

    @Test
    fun `remounting a Column sibling of a graphicsLayer tab row does not crash`() =
        runTaoSceneTest(width = 800, height = 480) {
            var tab by mutableStateOf(0)
            setContent {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(32.dp)) {
                        repeat(3) { index ->
                            Box(
                                Modifier
                                    .graphicsLayer {
                                        scaleX = if (tab == index) 1.05f else 1f
                                        scaleY = if (tab == index) 1.05f else 1f
                                    }.clickable { tab = index }
                                    .padding(8.dp),
                            ) {
                                Text("T$index")
                            }
                        }
                    }
                    // Same slot shape as the demo: body is a direct Column
                    // sibling of the animated tab row, not isolated in a Box.
                    when (tab) {
                        0 -> ScrollableButtonPage("one")
                        1 -> ScrollableButtonPage("two")
                        else -> ScrollableButtonPage("three")
                    }
                }
            }
            tab = 1
            frame()
            tab = 2
            frame()
            tab = 0
            frameUntilIdle()
        }

    @Test
    fun `remounting the body on pointer Press does not crash RectList`() =
        runTaoSceneTest(width = 800, height = 480) {
            var tab by mutableStateOf(0)
            setContent {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(40.dp)) {
                        repeat(3) { index ->
                            Button(
                                onClick = {},
                                modifier =
                                    Modifier
                                        .graphicsLayer {
                                            scaleX = if (tab == index) 1.05f else 1f
                                            scaleY = if (tab == index) 1.05f else 1f
                                        }.pointerInput(index) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    if (event.type == PointerEventType.Press) {
                                                        tab = index
                                                    }
                                                }
                                            }
                                        },
                            ) {
                                Text("T$index")
                            }
                        }
                    }
                    when (tab) {
                        0 -> ScrollableButtonPage("one")
                        1 -> ScrollableButtonPage("two")
                        else -> ScrollableButtonPage("three")
                    }
                }
            }
            // Press writes `tab` inside sendPointerEvent, then `frame()`
            // runs the production [TaoSceneBundle.render] — the nucleus-demo
            // onDragStarted path.
            click(x = 80f, y = 20f)
            click(x = 160f, y = 20f)
            click(x = 40f, y = 20f)
            frameUntilIdle()
        }

    @Test
    fun `Press remount plus animated graphicsLayer does not crash RectList`() =
        runTaoSceneTest(width = 800, height = 480) {
            var tab by mutableStateOf(0)
            setContent {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(40.dp)) {
                        repeat(3) { index ->
                            val scale by animateFloatAsState(if (tab == index) 1.05f else 1f)
                            Box(
                                Modifier
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }.pointerInput(index) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                if (event.type == PointerEventType.Press) {
                                                    tab = index
                                                }
                                            }
                                        }
                                    }.padding(8.dp),
                            ) {
                                Text("T$index")
                            }
                        }
                    }
                    when (tab) {
                        0 -> ScrollableButtonPage("one")
                        1 -> ScrollableButtonPage("two")
                        else -> ScrollableButtonPage("three")
                    }
                }
            }
            // Host path: Press remounts, then Move/Release measure before the
            // next paint. Do not insert a frame() between those events.
            moveMouse(x = 80f, y = 20f)
            pointerButton(PointerButton.Primary, pressed = true, render = false)
            pointerButton(PointerButton.Primary, pressed = false, render = false)
            frame()
            moveMouse(x = 160f, y = 20f)
            pointerButton(PointerButton.Primary, pressed = true, render = false)
            pointerButton(PointerButton.Primary, pressed = false, render = false)
            frameUntilIdle()
        }

    @Test
    fun `stress remount with animated graphicsLayer does not crash RectList`() =
        runTaoSceneTest(width = 800, height = 480) {
            var tab by mutableStateOf(0)
            setContent { RemountingTabChrome(tab) { tab = it } }
            // Alternate state remount (paint path) and Press remount without
            // an intervening frame (Tao input path). 80 cycles is enough to
            // catch a torn RectList that a 3-swap miss.
            repeat(80) { i ->
                tab = i % 3
                frame()
                val x = 40f + (i % 3) * 80f
                moveMouse(x = x, y = 20f)
                pointerButton(PointerButton.Primary, pressed = true, render = false)
                pointerButton(PointerButton.Primary, pressed = false, render = false)
                frame()
            }
            frameUntilIdle()
        }
}

@Composable
private fun RemountingTabChrome(
    tab: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp)) {
            repeat(3) { index ->
                val scale by animateFloatAsState(if (tab == index) 1.05f else 1f)
                Box(
                    Modifier
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }.pointerInput(index) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press) {
                                        onSelect(index)
                                    }
                                }
                            }
                        }.padding(8.dp),
                ) {
                    Text("T$index")
                }
            }
        }
        when (tab) {
            0 -> ScrollableButtonPage("one")
            1 -> ScrollableButtonPage("two")
            else -> ScrollableButtonPage("three")
        }
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
