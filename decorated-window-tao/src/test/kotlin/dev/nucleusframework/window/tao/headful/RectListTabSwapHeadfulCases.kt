package dev.nucleusframework.window.tao.headful

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.TitleBar
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Headful guard for the Compose 1.12 RectList remount crash through a real
 * [dev.nucleusframework.window.tao.DecoratedWindow] + [TitleBar] + animated
 * [graphicsLayer] tabs. See [dev.nucleusframework.window.tao.scene.TaoSceneBundle]:
 * this is a Tao host bug (AWT does not crash); the demo is not the fix.
 */
internal object RectListTabSwapHeadfulCases {
    private val selected = AtomicInteger(0)

    fun all(): List<TaoWindowTestCase> =
        listOf(
            TaoWindowTestCase(
                name = "title-bar Press remount does not crash RectList",
                timeoutMillis = 30_000L,
                content = {
                    var tab by remember { mutableIntStateOf(0) }
                    LaunchedEffect(Unit) {
                        delay(300)
                        // Stress the real DecoratedWindow frame loop: remount
                        // the body every frame-ish while graphicsLayer tabs
                        // animate. 40 swaps is well past a lucky 3-tab miss.
                        repeat(40) { i ->
                            tab = (i + 1) % 3
                            selected.set(i + 1)
                            delay(32)
                        }
                    }
                    TitleBar { _ ->
                        repeat(3) { index ->
                            val scale by animateFloatAsState(if (tab == index) 1.05f else 1f)
                            Button(
                                onClick = {},
                                modifier =
                                    Modifier.graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    },
                            ) {
                                Text("T$index")
                            }
                        }
                    }
                    when (tab) {
                        0 -> ScrollPage("one")
                        1 -> ScrollPage("two")
                        else -> ScrollPage("three")
                    }
                },
            ) {
                selected.set(0)
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("body remounted through the Tao frame loop") { selected.get() >= 30 }
                settle()
            },
        )
}

@androidx.compose.runtime.Composable
private fun ScrollPage(label: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        repeat(20) { index ->
            Button(
                onClick = {},
                modifier = Modifier.fillMaxWidth().padding(8.dp).graphicsLayer { alpha = 1f },
            ) {
                Text("$label-$index")
            }
        }
    }
}
