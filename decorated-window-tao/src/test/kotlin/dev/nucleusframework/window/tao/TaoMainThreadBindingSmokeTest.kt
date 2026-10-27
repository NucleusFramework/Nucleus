package dev.nucleusframework.window.tao

import androidx.compose.runtime.SideEffect
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.concurrent.thread
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * Opt-in end-to-end test (set `NUCLEUS_TAO_SMOKE=1`) for #829: the thread
 * recorded as the Tao main thread must be the one that actually runs the
 * event loop's callbacks.
 *
 * The Gradle test worker never runs on macOS thread 0, so `nativeRunBlocking`
 * marshals the loop onto AppKit's main thread — exactly an ordinary launch
 * without `-XstartOnFirstThread`. Before the fix the calling thread stayed
 * recorded and `Dispatchers.Main.immediate` asked to dispatch from inside its
 * own composition.
 */
class TaoMainThreadBindingSmokeTest {
    @Test
    fun mainImmediateNeedsNoDispatchInsideComposition() {
        if (System.getenv("NUCLEUS_TAO_SMOKE") == null) {
            println("SKIPPED: set NUCLEUS_TAO_SMOKE=1 to run the main-thread binding e2e test")
            return
        }
        // The loop takes over this thread: halt instead of hanging the forked
        // JVM if the window never reaches exitApplication.
        val bailout =
            thread(isDaemon = true, name = "tao-main-thread-smoke-bailout") {
                try {
                    Thread.sleep(BAILOUT_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                Runtime.getRuntime().halt(BAILOUT_EXIT_CODE)
            }

        var callbackThread: Thread? = null
        var recordedThread: Thread? = null
        var dispatchNeeded: Boolean? = null
        try {
            taoApplication(exitProcessOnExit = false) {
                DecoratedWindow(onCloseRequest = ::exitApplication, title = "main-thread-smoke") {
                    SideEffect {
                        if (dispatchNeeded == null) {
                            callbackThread = Thread.currentThread()
                            recordedThread = TaoMainDispatcher.taoMainThread
                            dispatchNeeded = Dispatchers.Main.immediate.isDispatchNeeded(EmptyCoroutineContext)
                            exitApplication()
                        }
                    }
                }
            }
        } finally {
            bailout.interrupt()
        }

        assertSame(callbackThread, recordedThread, "recorded Tao thread differs from the UI callback thread")
        assertEquals(false, dispatchNeeded, "Dispatchers.Main.immediate asked to dispatch on its own UI thread")
        assertFalse(callbackThread == null, "the window never composed")
    }

    private companion object {
        const val BAILOUT_MS = 60_000L
        const val BAILOUT_EXIT_CODE = 42
    }
}
