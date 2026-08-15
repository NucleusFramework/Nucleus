package dev.nucleusframework.window.tao.headful

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
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
     * When non-null, the suite also composes a [dev.nucleusframework.window.tao.DecoratedDialog]
     * at application scope (parented to this case's window). [dialogSize]
     * is its [androidx.compose.ui.window.DialogState.size].
     */
    val dialogSize: DpSize? = null,
    val dialogContent: (@Composable TaoDecoratedDialogScope.() -> Unit)? = null,
    /** Optional extra window content composed inside the DecoratedWindow. */
    val content: @Composable TaoDecoratedWindowScope.() -> Unit = {},
    val driver: suspend TaoWindowTestScope.() -> Unit,
) {
    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}

internal class TaoWindowTestScope(
    val window: TaoWindow,
    val dialogWindow: TaoWindow? = null,
) {
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

internal class TaoWindowTestResult(
    val name: String,
    val failure: Throwable?,
    val skippedReason: String? = null,
    val durationMillis: Long,
)
