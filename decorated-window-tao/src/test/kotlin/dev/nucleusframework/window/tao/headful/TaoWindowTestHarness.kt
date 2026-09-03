package dev.nucleusframework.window.tao.headful

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.window.tao.ApplicationScope
import dev.nucleusframework.window.tao.SatelliteWindowState
import dev.nucleusframework.window.tao.TaoDecoratedDialogScope
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.TaoWindow
import kotlinx.coroutines.delay

/**
 * Stage-2 harness: real Tao windows, one process, one event loop.
 *
 * The Tao event loop can only run once per process (and on macOS AppKit only
 * accepts window creation from thread 0, hence the `-XstartOnFirstThread`
 * JavaExec launcher in build.gradle.kts). So instead of one JVM per test
 * (kotlin-desktop-toolkit's `forkEvery = 1`), this follows Compose Desktop's
 * `runApplicationTest` model: a single `taoApplication` hosts every test
 * case sequentially — each case gets a fresh [dev.nucleusframework.window.tao.DecoratedWindow],
 * drives it from a `LaunchedEffect` (suspending, never blocking the loop),
 * and the window is disposed before the next case starts.
 *
 * Run via `./gradlew :decorated-window-tao:taoHeadfulTest` — needs a display
 * (real session on macOS/Windows runners, Xvfb+WM on Linux CI).
 */
internal class TaoWindowTestCase(
    val name: String,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** Platforms the case runs on; empty = all. */
    val skip: () -> String? = { null },
    /**
     * When true (default), the suite paints a full-size DarkGray chrome under
     * [content]. Cases that need an empty / alpha-cleared client (e.g. fully
     * transparent window probes) set this to false.
     */
    val paintDefaultBackground: Boolean = true,
    /**
     * Forwarded to [dev.nucleusframework.window.tao.DecoratedWindow]'s
     * `transparent` parameter (#416). Creation-time only.
     */
    val transparent: Boolean = false,
    /**
     * Forwarded to [dev.nucleusframework.window.tao.DecoratedWindow]'s
     * `nativePopupLayers`: Compose `Popup` content becomes a real Tao popup
     * window instead of being drawn inline. Creation-time only.
     */
    val nativePopupLayers: Boolean = false,
    /**
     * Initial [androidx.compose.ui.window.WindowState.size] for this case's
     * [dev.nucleusframework.window.tao.DecoratedWindow]. `null` keeps the
     * Compose Desktop default (800×600). Pass a [DpSize] with
     * [androidx.compose.ui.unit.Dp.Unspecified] on one or both axes to
     * exercise wrap-content sizing (#532).
     */
    val size: DpSize? = null,
    /**
     * When non-null, the suite uses this [WindowState] instead of a fresh
     * [androidx.compose.ui.window.rememberWindowState]. Lets a case animate
     * or otherwise mutate size the same way an app drives
     * `DecoratedWindow(state)` (#576).
     */
    val windowState: WindowState? = null,
    /**
     * When non-null, the suite drives the window through the AWT-free window
     * API v2 clone ([dev.nucleusframework.window.tao.v2.WindowState]) instead of
     * a v1 state. Takes precedence over [windowState] / [size]: the clone's own
     * bounds provider owns the initial geometry.
     */
    val nucleusWindowState: dev.nucleusframework.window.tao.v2.WindowState? = null,
    /**
     * When non-null, the suite also composes a [dev.nucleusframework.window.tao.DecoratedDialog]
     * at application scope (parented to this case's window). [dialogSize]
     * is its [androidx.compose.ui.window.DialogState.size].
     */
    val dialogSize: DpSize? = null,
    val dialogContent: (@Composable TaoDecoratedDialogScope.() -> Unit)? = null,
    /**
     * Whether the dialog is in composition. Defaults to `true`; a driver flips
     * it to `false` to close the dialog the way an app would — by dropping it.
     */
    val dialogVisible: MutableState<Boolean> = mutableStateOf(true),
    /**
     * When non-null, the suite composes a
     * [dev.nucleusframework.window.tao.SatelliteWindow] *inside* this case's
     * window content — so it picks the case window up as its parent through
     * `LocalTaoWindow` — driven by this state. The case keeps the reference and
     * asserts against the anchoring state it publishes.
     */
    val satelliteState: SatelliteWindowState? = null,
    /**
     * When non-null, the satellite is composed at *application* scope with an
     * explicit `parent` picked from this state — the reparenting call site —
     * instead of inside the case window's content. Flip it from the driver.
     */
    val satelliteOwner: MutableState<SatelliteOwner>? = null,
    /** Forwarded to the satellite's `hideWhileParentFullscreenOrMaximized`. */
    val satelliteHideWhileParentFills: Boolean = true,
    /** Routed to the satellite's `onCloseRequest`; the suite never drops the satellite itself. */
    val satelliteOnCloseRequest: () -> Unit = {},
    /** Content of the satellite window; ignored without a [satelliteState]. */
    val satelliteContent: @Composable TaoDecoratedWindowScope.() -> Unit = {},
    /** Optional extra window content composed inside the DecoratedWindow. */
    val content: @Composable TaoDecoratedWindowScope.() -> Unit = {},
    /**
     * Extra application-scope content composed next to the case window and
     * dialog — for cases whose windows are declared at application level, such
     * as workspace satellites. Receives the case's published windows and is
     * recomposed as they appear.
     */
    val applicationContent: (@Composable ApplicationScope.(HeadfulWindows) -> Unit)? = null,
    val driver: suspend TaoWindowTestScope.() -> Unit,
) {
    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}

/** The suite's windows as published so far, handed to [TaoWindowTestCase.applicationContent]. */
internal class HeadfulWindows(
    val window: TaoWindow?,
    val dialog: TaoWindow?,
)

/** Which of the suite's windows owns the satellite — see [TaoWindowTestCase.satelliteOwner]. */
internal enum class SatelliteOwner {
    CaseWindow,
    DialogWindow,
}

internal class TaoWindowTestScope(
    val window: TaoWindow,
    val dialogWindow: TaoWindow? = null,
    val satelliteWindow: TaoWindow? = null,
) {
    /** Outer bounds of the satellite window as `[x, y, w, h]` physical px. */
    fun satelliteBounds(): LongArray? = satelliteWindow?.outerBoundsPx()

    /**
     * Polls [predicate] on the composition dispatcher (the Tao main thread)
     * until it holds — suspension keeps the event loop running in between.
     */
    suspend fun awaitUntil(
        description: String,
        timeoutMillis: Long = AWAIT_TIMEOUT_MILLIS,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!predicate()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $description" }
            delay(POLL_MILLIS)
        }
    }

    /** Lets the loop breathe for a fixed settle period. */
    suspend fun settle(millis: Long = SETTLE_MILLIS) = delay(millis)

    /** Outer bounds as [x, y, w, h] physical px, or null before the window is mapped. */
    fun bounds(): LongArray? = window.outerBoundsPx()

    private companion object {
        const val AWAIT_TIMEOUT_MILLIS = 15_000L
        const val POLL_MILLIS = 25L
        const val SETTLE_MILLIS = 300L
    }
}

/**
 * `true` once the platform reports a frame with a real size for this window.
 *
 * `> 1`, not `> 0`: GTK maps a window at 1x1 until its first allocation, so a
 * gate that only rules out zero lets a case measure the placeholder — a
 * torn-off window "1 dp wide", a satellite anchored against a 1px-tall child.
 * Slow, software-rendered hosts (the CI Xvfb runner) hold that placeholder for
 * several frames where a real session passes through it in one.
 */
@Suppress("MagicNumber") // outer frame is [x, y, w, h]
internal fun TaoWindow.hasRealFramePx(): Boolean {
    val rect = outerBoundsPx() ?: return false
    return rect[2] > 1L && rect[3] > 1L
}

internal class TaoWindowTestResult(
    val name: String,
    val failure: Throwable?,
    val skippedReason: String? = null,
    val durationMillis: Long,
)
