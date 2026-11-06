package hotkeydemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.core.runtime.ActivationToken
import dev.nucleusframework.globalhotkey.GlobalHotKeyManager
import dev.nucleusframework.globalhotkey.HotKeyEvent
import dev.nucleusframework.globalhotkey.HotKeyEventListener
import dev.nucleusframework.globalhotkey.HotKeyModifier
import dev.nucleusframework.globalhotkey.HotKeyState
import dev.nucleusframework.globalhotkey.plus
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import kotlinx.coroutines.delay
import java.awt.event.KeyEvent.VK_PAUSE
import java.io.File
import kotlin.time.Duration.Companion.seconds

// `token` (default): requestFocus() takes the hotkey's activation token on its own. `plain` drops
// the token first — the behaviour before #739, which Wayland refuses.
private val mode = System.getenv("HOTKEY_DEMO_MODE") ?: "token"

// `visible` (default): the window stays mapped, behind whatever the user moves on to. `hidden`: it
// hides after launch, quick-entry style, and the hotkey maps it again.
private val startHidden = System.getenv("HOTKEY_DEMO_START") == "hidden"
private val logFile =
    File(
        System.getenv("HOTKEY_DEMO_LOG") ?: "${System.getProperty("java.io.tmpdir")}/hotkey-activation-demo.log",
    )

private fun log(message: String) {
    println(message)
    runCatching { logFile.appendText("$message\n") }
}

private fun HotKeyEvent.describe(): String =
    "event state=$state repeat=$isRepeat timestamp=$timestamp token=${if (activationToken != null) "yes" else "no"}"

fun main(args: Array<String>) =
    nucleusApplication(args) {
        var status by remember { mutableStateOf("Press Ctrl+Alt+Shift+Pause") }
        var visible by remember { mutableStateOf(true) }
        NucleusDecoratedWindowTheme(isDark = true) {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                visible = visible,
                title = "Quick entry",
                state = rememberWindowState(size = DpSize(480.dp, 200.dp)),
                // The E2E's oracle: a key typed after the hotkey reaches this window only if it has focus.
                onPreviewKeyEvent = {
                    log("key ${it.key} ${it.type}")
                    false
                },
            ) {
                val window = LocalNucleusWindow.current
                LaunchedEffect(window) { window.focusFlow.collect { log("focus $it") } }
                LaunchedEffect(Unit) {
                    delay(1.seconds)
                    if (startHidden) visible = false
                    log("ready")
                }
                DisposableEffect(window) {
                    log("mode $mode")
                    if (!GlobalHotKeyManager.initialize()) log("init failed ${GlobalHotKeyManager.lastError}")
                    val handle =
                        GlobalHotKeyManager.register(
                            keyCode = VK_PAUSE,
                            modifiers = HotKeyModifier.CONTROL + HotKeyModifier.ALT + HotKeyModifier.SHIFT,
                            description = "Open quick entry",
                            listener =
                                HotKeyEventListener { event ->
                                    log(event.describe())
                                    if (event.state == HotKeyState.PRESSED && !event.isRepeat) {
                                        status = "Opened by the hotkey"
                                        visible = true
                                        window.show()
                                        if (mode == "plain") ActivationToken.take()
                                        window.requestFocus()
                                    }
                                },
                        )
                    val bound = GlobalHotKeyManager.commitRegistrations()
                    log(
                        "registered handle=$handle bound=$bound shortcut=${GlobalHotKeyManager.portalShortcutId(
                            handle,
                        )} error=${GlobalHotKeyManager.lastError}",
                    )
                    onDispose { GlobalHotKeyManager.shutdown() }
                }
                TitleBar { BasicText("Quick entry", style = TextStyle(color = Color.White)) }
                Column(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF1565C0)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(status, style = TextStyle(color = Color.White, fontSize = 24.sp))
                }
            }
        }
    }
