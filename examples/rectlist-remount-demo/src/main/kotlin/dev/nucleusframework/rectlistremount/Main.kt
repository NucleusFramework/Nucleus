package dev.nucleusframework.rectlistremount

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.TitleBar
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * Self-driven E2E for the Tao + Compose 1.12 RectList remount crash
 * (`LayoutNode N not found in RectList`). Opens a real [DecoratedWindow],
 * remounts a large exclusive body next to [graphicsLayer] title-bar siblings
 * on the Tao frame loop, then [exitProcess] 0. An uncaught RectList throw
 * fails the JVM.
 *
 * Remounts are driven from composition (not from `TaoWindow.dispatch` —
 * that re-enters the native frame and freezes the window). The Press path
 * is covered by `TaoSceneContentSwapTest`. Not nucleus-demo.
 */
private const val REMOUNT_COUNT = 40

fun main(args: Array<String>) {
    nucleusApplication(args) {
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "RectList remount repro",
            state = rememberWindowState(size = DpSize(800.dp, 480.dp)),
        ) {
            var tab by remember { mutableIntStateOf(0) }
            var remounts by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                delay(300)
                repeat(REMOUNT_COUNT) { i ->
                    tab = (i + 1) % 3
                    remounts = i + 1
                    delay(32)
                }
                println("RECTLIST_REMOUNT_OK remounts=$remounts")
                exitProcess(0)
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
        }
    }
}

@Composable
private fun ScrollPage(label: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        repeat(24) { index ->
            Button(
                onClick = {},
                modifier = Modifier.fillMaxWidth().padding(8.dp).graphicsLayer { alpha = 1f },
            ) {
                Text("$label-$index")
            }
        }
    }
}
