package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.window.tao.DecoratedWindow
import dev.nucleusframework.window.tao.taoApplication
import kotlinx.coroutines.delay

/**
 * Manual smoke for the #622 fatal-error path: shows a plain window, then
 * throws from a `LaunchedEffect` after
 * `-Dnucleus.tao.fatal.smoke.crashAfterMs=` (default 3000 ms).
 *
 * Expected outcome, in order:
 * 1. SEVERE "Unhandled exception on the Tao main thread — closing" log,
 * 2. the window closes and the blocking native error dialog appears
 *    (NSAlert-less CFUserNotification / MessageBoxW / GtkMessageDialog),
 * 3. dismissing it ends the process with **exit code 1** (Gradle reports
 *    "finished with non-zero exit value 1" — that is the pass signal).
 *
 * Run: `./gradlew :decorated-window-tao:taoFatalDialogSmoke`
 */
object FatalErrorDialogSmokeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val crashAfterMs =
            System.getProperty("nucleus.tao.fatal.smoke.crashAfterMs")?.toLongOrNull() ?: 3_000L
        taoApplication {
            DecoratedWindow(
                onCloseRequest = { /* smoke owns lifecycle — the crash exits */ },
                state = rememberWindowState(size = DpSize(480.dp, 320.dp)),
                title = "tao fatal dialog smoke #622",
            ) {
                Box(Modifier.fillMaxSize().background(Color(0xFF224466)))
                LaunchedEffect(Unit) {
                    delay(crashAfterMs)
                    error("Deliberate fatal from FatalErrorDialogSmokeMain (#622)")
                }
            }
        }
    }
}
