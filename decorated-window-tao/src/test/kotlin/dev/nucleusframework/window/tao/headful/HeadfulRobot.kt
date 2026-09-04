package dev.nucleusframework.window.tao.headful

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Robot
import java.awt.event.InputEvent
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

    @Volatile
    private var lastAim: String? = null

    /**
     * Where the last gesture aimed and where the pointer actually ended up, or
     * `null` before any gesture.
     *
     * A headful pointer case that times out says nothing on its own — "the
     * drag started" never held — and the two ways it gets there look the same
     * from the outside: the point was computed wrong (a window frame read
     * before the platform had one), or the point was right and the press
     * never reached the window. Reporting both the requested and the observed
     * position tells them apart from a CI log.
     */
    val lastAimReport: String
        get() = lastAim ?: "no gesture yet"

    /** Where the last gesture aimed, in logical screen points, or `null`. */
    @Volatile
    var lastAimPoint: Point? = null
        private set

    /** Whether a press has been injected since the last release — see [releaseEveryButton]. */
    @Volatile
    private var buttonMayBeHeld = false

    /** Records that a press is about to be injected. */
    fun notePress() {
        buttonMayBeHeld = true
    }

    /** Records where [x] / [y] was aimed and where the pointer landed. */
    fun noteAim(
        x: Int,
        y: Int,
    ) {
        val landed = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        lastAimPoint = Point(x, y)
        lastAim = "aimed ($x, $y), pointer at ${landed?.let { "(${it.x}, ${it.y})" } ?: "unknown"}"
    }

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

    /**
     * Lets go of every mouse button, whatever the case that held one did.
     *
     * A case that fails between its press and its release leaves the button
     * down *at the X server*, and a `mousePress` on an already-pressed button
     * is a no-op: every later robot case then aims correctly, moves the
     * pointer correctly, and receives nothing. One red case turns the whole
     * rest of the robot suite red with it, and the log gives no hint that the
     * first one is the only real failure. Run after every case.
     *
     * Only after a press, though: `CRobot.mouseEvent` segfaults the JVM on
     * macOS when it is asked to release a button that was never pressed, and
     * that would take down a suite where most cases never touch the robot at
     * all.
     */
    suspend fun releaseEveryButton() {
        if (unavailable != null || !buttonMayBeHeld) return
        buttonMayBeHeld = false
        inject { robot ->
            for (mask in BUTTON_MASKS) robot.mouseRelease(mask)
            true
        }
    }

    private fun robot(): Robot =
        cached ?: Robot()
            .apply {
                autoDelay = AUTO_DELAY_MILLIS
                isAutoWaitForIdle = false
            }.also { cached = it }

    private val BUTTON_MASKS =
        intArrayOf(
            InputEvent.BUTTON1_DOWN_MASK,
            InputEvent.BUTTON2_DOWN_MASK,
            InputEvent.BUTTON3_DOWN_MASK,
        )

    private const val INJECT_TIMEOUT_MILLIS = 5_000L
    private const val AUTO_DELAY_MILLIS = 30
}
