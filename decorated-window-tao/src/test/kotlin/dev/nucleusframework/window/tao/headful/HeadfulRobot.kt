package dev.nucleusframework.window.tao.headful

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Robot
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * AWT Robot input injection for the headful suite, driven off the Tao
 * event-loop thread.
 *
 * Case drivers run on the Tao main thread, so a Robot call that never returns
 * freezes the event loop and the global watchdog halts the JVM — every result
 * of the run is lost, not just the offending case. Two real hosts do exactly
 * that:
 *
 * - macOS without the Accessibility TCC grant: `new Robot()` blocks.
 * - Wayland: the JDK routes injection through the XDG RemoteDesktop portal
 *   (`sun.awt.screencast.ScreencastHelper`). When the compositor refuses the
 *   session ("Session is not allowed to call NotifyPointer methods"),
 *   `mousePress` blocks forever inside the native call.
 *
 * So every gesture runs on [Dispatchers.IO] under a timeout, and the first
 * timeout latches [unavailableReason] — later calls fail fast instead of
 * parking another thread on the same wedged native lock.
 */
internal object HeadfulRobot {
    @Volatile
    private var unavailable: String? = null

    @Volatile
    private var cached: Robot? = null

    /** Why input injection is unusable on this host, or null while it works. */
    val unavailableReason: String?
        get() = unavailable

    /**
     * Runs [gesture] with a shared [Robot] off the event loop, giving up after
     * [timeoutMillis]. Returns null when the host cannot inject input — the
     * call blocked, threw, or an earlier call already latched unavailable.
     *
     * [gesture] runs on an IO thread, so blocking `Thread.sleep` pauses between
     * synthetic events are fine (and are what Robot's own autoDelay does).
     */
    @Suppress("SwallowedException")
    suspend fun <T : Any> inject(
        timeoutMillis: Long = INJECT_TIMEOUT_MILLIS,
        gesture: (Robot) -> T,
    ): T? {
        if (unavailable != null) return null
        return withContext(Dispatchers.IO) {
            // supplyAsync, not a plain call: a wedged native injection must be
            // abandonable, and only a separate thread can be left behind.
            val future = CompletableFuture.supplyAsync { gesture(robot()) }
            try {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (t: TimeoutException) {
                unavailable = "AWT Robot injection blocked for ${timeoutMillis}ms (see HeadfulRobot)"
                System.err.println("[HeadfulRobot] unavailable: $unavailable")
                null
            } catch (e: ExecutionException) {
                unavailable = "AWT Robot injection failed: ${e.cause ?: e}"
                System.err.println("[HeadfulRobot] unavailable: $unavailable")
                null
            }
        }
    }

    private fun robot(): Robot =
        cached ?: Robot()
            .apply {
                autoDelay = AUTO_DELAY_MILLIS
                isAutoWaitForIdle = false
            }.also { cached = it }

    private const val INJECT_TIMEOUT_MILLIS = 5_000L
    private const val AUTO_DELAY_MILLIS = 30
}
