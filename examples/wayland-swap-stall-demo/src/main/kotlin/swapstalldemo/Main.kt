package swapstalldemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

// #958: on native Wayland, a window that stays "visible" while the compositor withholds its frame
// callbacks (scrolled off-screen on niri, fully covered on GNOME) parks its swap thread inside
// eglSwapBuffers on its next redraw. Opening or closing another window then runs that window's EGL
// attach / detach on the event-loop thread.
//
//   1. The main window redraws on a timer, so its swap thread presents again and again.
//   2. A maximized, opaque cover window hides it (-Dswapstall.trigger=cover, the default). Mutter
//      culls the covered surface and stops its frame callbacks; Nucleus does not know, so it keeps
//      rendering, and the main window's next swap waits for a callback that never comes.
//      Not a minimize: Nucleus skips rendering a window it knows is minimized, so nothing parks.
//   3. A second window opens, then closes, CYCLES times.
//
// A watchdog thread checks a heartbeat ticked on the event-loop thread. When the loop stops for
// HANG_MS it prints the main and swap threads' stacks and exits with status 2. Status 0 = no hang.
// SWAP_STALL_HOLD=1 leaves a hung process alive for a debugger instead.
//
// -Dswapstall.trigger=manual hides nothing: hide the main window yourself (scroll it off-screen on
// niri) and the second window keeps cycling until the app is closed.

private const val HANG_MS = 6_000L
private const val CYCLES = 3
private val start = System.nanoTime()

@Volatile private var heartbeat = System.nanoTime()

@Volatile private var phase = "starting"

// Frames the main window actually rendered (its frame clock only ticks when it renders).
@Volatile private var mainRendered = 0L

private fun log(message: String) {
    println("[%7.3f] E2E %s".format((System.nanoTime() - start) / 1e9, message))
}

private fun startWatchdog() {
    Thread({
        while (true) {
            Thread.sleep(200)
            val stalledMs = (System.nanoTime() - heartbeat) / 1_000_000
            if (stalledMs > HANG_MS) {
                log("HANG: event loop stalled for $stalledMs ms during '$phase' (pid ${ProcessHandle.current().pid()})")
                Thread
                    .getAllStackTraces()
                    .filterKeys { it.name == "main" || it.name.startsWith("TaoSwapThread") }
                    .forEach { (thread, stack) ->
                        println("\"${thread.name}\" ${thread.state}")
                        stack.take(12).forEach { println("    at $it") }
                    }
                if (System.getenv("SWAP_STALL_HOLD") != null) {
                    log("SWAP_STALL_HOLD set: leaving the process hung for a debugger")
                    Thread.sleep(Long.MAX_VALUE)
                }
                Runtime.getRuntime().halt(2)
            }
        }
    }, "swap-stall-watchdog").apply { isDaemon = true }.start()
}

fun main() {
    val manual = System.getProperty("swapstall.trigger") == "manual"
    startWatchdog()
    nucleusApplication {
        var coverOpen by remember { mutableStateOf(false) }
        var secondOpen by remember { mutableStateOf(false) }

        DecoratedWindow(
            onCloseRequest = { exitProcess(0) },
            title = "SwapStallDemo Main",
            state = rememberWindowState(width = 480.dp, height = 320.dp),
        ) {
            var frame by remember { mutableLongStateOf(0L) }
            LaunchedEffect(Unit) {
                // A timer, not withFrameNanos: the frame clock stops with the window's frame
                // callbacks, a state change from a timer keeps asking for redraws (#958 step 3).
                while (true) {
                    delay(16)
                    frame++
                }
            }
            LaunchedEffect(Unit) {
                while (true) withFrameNanos { mainRendered++ }
            }
            Box(Modifier.fillMaxSize().background(Color.hsv((frame % 360).toFloat(), 0.6f, 0.8f)))

            LaunchedEffect(Unit) {
                // Heartbeat on the event-loop thread (Dispatchers.Main).
                while (true) {
                    heartbeat = System.nanoTime()
                    delay(50)
                }
            }
            LaunchedEffect(Unit) {
                delay(1_500)
                if (!manual) {
                    phase = "cover main"
                    log("covering the main window with a maximized one")
                    coverOpen = true
                    delay(1_500)
                }
                var cycle = 0
                while (manual || cycle < CYCLES) {
                    cycle++
                    phase = "open second #$cycle"
                    log("opening the second window #$cycle (main window rendered $mainRendered frames)")
                    secondOpen = true
                    delay(1_500)
                    phase = "close second #$cycle"
                    log("closing the second window #$cycle")
                    secondOpen = false
                    delay(1_500)
                }
                log("done: no hang (main window rendered $mainRendered frames)")
                exitProcess(0)
            }
        }

        if (coverOpen) {
            DecoratedWindow(
                onCloseRequest = { coverOpen = false },
                title = "SwapStallDemo Cover",
                state = rememberWindowState(placement = WindowPlacement.Maximized),
            ) {
                Box(Modifier.fillMaxSize().background(Color(0xFF202020)))
            }
        }

        if (secondOpen) {
            DecoratedWindow(
                onCloseRequest = { secondOpen = false },
                title = "SwapStallDemo Second",
                state = rememberWindowState(width = 320.dp, height = 200.dp),
            ) {
                Box(Modifier.fillMaxSize().background(Color(0xFF805020)))
                LaunchedEffect(Unit) { log("second window composed") }
            }
        }
    }
}
