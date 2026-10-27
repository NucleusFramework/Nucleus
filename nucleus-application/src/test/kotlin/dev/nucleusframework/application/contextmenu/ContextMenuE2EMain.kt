@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * The loggers whose trace [main] forwards, held for the process's lifetime:
 * `java.util.logging` keeps only a weak reference to a logger, so a collected
 * one silently loses the configuration installed on it.
 */
private var tracedLoggers: List<Logger> = emptyList()

/**
 * Process-level fixture for the compositor-driven context menu E2E
 * (`scripts/context-menu-wayland-e2e.py`): one window painted a flat green,
 * whose whole content is a [ContextMenuArea] using the OS-looking menu
 * (`nativeContextMenu = true`, popups otherwise in-scene). Everything the
 * driver needs to correlate with its screenshots goes to stdout, timestamped
 * in milliseconds since start: pointer presses and releases as the scene sees
 * them, every context menu status change, window focus flips, item clicks.
 *
 * Environment: `NUCLEUS_E2E_WINDOW_W` / `NUCLEUS_E2E_WINDOW_H` (dp, default
 * 900×600).
 */
fun main(args: Array<String>) {
    val startNanos = System.nanoTime()

    fun log(message: String) {
        val ms = (System.nanoTime() - startNanos) / 1_000_000
        println("[e2e $ms] $message")
        System.out.flush()
    }
    // The popup layer's FINE trace, on the same clock as the lines above.
    tracedLoggers =
        listOf("dev.nucleusframework.window.tao.popup", "dev.nucleusframework.window.tao.scene").map { name ->
            Logger.getLogger(name).apply {
                level = Level.FINE
                useParentHandlers = false
                addHandler(
                    object : Handler() {
                        override fun publish(record: LogRecord) = log("LOG ${record.message}")

                        override fun flush() = Unit

                        override fun close() = Unit
                    }.apply { level = Level.ALL },
                )
            }
        }
    val width = System.getenv("NUCLEUS_E2E_WINDOW_W")?.toIntOrNull() ?: 900
    val height = System.getenv("NUCLEUS_E2E_WINDOW_H")?.toIntOrNull() ?: 600
    nucleusApplication(args, enableSingleInstance = false) {
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(size = DpSize(width.dp, height.dp)),
            title = "context-menu-e2e",
            // NUCLEUS_E2E_NATIVE_CONTEXT_MENU=0 is the control: Compose's own
            // in-scene menu, so a symptom can be attributed to the native
            // surface or to Compose itself.
            nativeContextMenu = System.getenv("NUCLEUS_E2E_NATIVE_CONTEXT_MENU") != "0",
        ) {
            val state = remember { ContextMenuState() }
            val windowInfo = LocalWindowInfo.current
            LaunchedEffect(Unit) { log("window content composed") }
            LaunchedEffect(state) {
                snapshotFlow { state.status }.collect { status ->
                    when (status) {
                        is ContextMenuState.Status.Open -> log("menu OPEN at ${status.rect.center}")
                        else -> log("menu CLOSED")
                    }
                }
            }
            LaunchedEffect(windowInfo) {
                snapshotFlow { windowInfo.isWindowFocused }.collect { log("window focused=$it") }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF00FF00))
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press || event.type == PointerEventType.Release) {
                                    val change = event.changes.first()
                                    log(
                                        "pointer ${event.type} at ${change.position} " +
                                            "secondary=${event.buttons.isSecondaryPressed} " +
                                            "primary=${event.buttons.isPrimaryPressed}",
                                    )
                                }
                            }
                        }
                    },
            ) {
                ContextMenuArea(
                    items = {
                        listOf(
                            ContextMenuItem("Alpha") { log("item Alpha") },
                            ContextMenuItem("Bravo") { log("item Bravo") },
                            ContextMenuItem("Charlie") { log("item Charlie") },
                            ContextMenuItem("Delta") { log("item Delta") },
                        )
                    },
                    state = state,
                ) {
                    Box(Modifier.fillMaxSize())
                }
                // Text context menu path (NativeTextContextMenu): a field in the
                // top-left corner, 20..420 × 20..60 dp.
                var text by remember { mutableStateOf(TextFieldValue("right click in this field")) }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier =
                        Modifier
                            .offset(20.dp, 20.dp)
                            .size(400.dp, 40.dp)
                            .background(Color.White)
                            .padding(8.dp),
                )
            }
        }
    }
}
