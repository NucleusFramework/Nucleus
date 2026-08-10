package dev.nucleusframework.window.tao

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Opt-in smoke test (set `NUCLEUS_TAO_SMOKE=1`) — opens a real Tao window and
 * verifies runtime resizability parity with the AWT backends (#260): driving
 * the `resizable` parameter to `false` after the window is shown must update
 * [TaoWindow.isResizable] and the scope's [DecoratedWindowState.isResizable].
 *
 * Not run by default: it takes over the calling thread with the native event
 * loop and needs a display.
 */
class TaoRuntimeResizableSmokeTest {
    @Test
    fun runtimeResizableToggleAppliesAndRecomposes() {
        if (System.getenv("NUCLEUS_TAO_SMOKE") == null) {
            println("SKIPPED: set NUCLEUS_TAO_SMOKE=1 to run the real-window Tao smoke test")
            return
        }

        val windowFlag = AtomicReference<Boolean>()
        val stateFlag = AtomicReference<Boolean>()

        // The tao event loop takes over this thread; if exitApplication is
        // never reached the forked test JVM would hang with no timeout.
        // Disarmed via interrupt once the loop returns: the Gradle executor
        // JVM is shared, and a still-armed watchdog would halt whatever test
        // class happens to run WATCHDOG_MS later.
        val watchdog =
            thread(isDaemon = true, name = "tao-smoke-watchdog") {
                try {
                    Thread.sleep(WATCHDOG_MS)
                } catch (_: InterruptedException) {
                    return@thread // test finished in time
                }
                Runtime.getRuntime().halt(WATCHDOG_EXIT_CODE)
            }

        taoApplication {
            var resizable by remember { mutableStateOf(true) }
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                title = "resizable-smoke",
                resizable = resizable,
            ) {
                val scope = this
                LaunchedEffect(Unit) {
                    delay(SETTLE_MS) // let the window map and paint
                    resizable = false
                    delay(APPLY_MS) // let the re-apply effect + recomposition run
                    windowFlag.set(scope.window.isResizable)
                    stateFlag.set(scope.state.isResizable)
                    exitApplication()
                }
            }
        }
        watchdog.interrupt()

        assertEquals(
            false,
            windowFlag.get(),
            "TaoWindow.isResizable did not flip after driving resizable=false at runtime",
        )
        assertEquals(
            false,
            stateFlag.get(),
            "state.isResizable did not flip after driving resizable=false at runtime",
        )
    }

    private companion object {
        const val SETTLE_MS = 3_000L
        const val APPLY_MS = 2_000L
        const val WATCHDOG_MS = 120_000L
        const val WATCHDOG_EXIT_CODE = 42
    }
}
