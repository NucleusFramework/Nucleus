package dev.nucleusframework.window.tao

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Top-level launcher for the Tao backend. Mirrors Compose Desktop's
 * `application { }` but pumps the Compose runtime through Tao's event loop
 * via [TaoMainDispatcher] (single-threaded model — no AWT EDT).
 *
 * Must be called from the macOS main thread (process thread 0). In a GraalVM
 * native-image build this is automatic; on a regular JVM, launch with
 * `-XstartOnFirstThread`.
 *
 * Inside [content] you may call `@Composable` [DecoratedWindow]s, use
 * `LaunchedEffect`/`DisposableEffect`, observe `MutableState`, etc. The
 * composition lives until [ApplicationScope.exitApplication] is called.
 *
 * The JVM is terminated with `exitProcess(0)` once the Tao event loop
 * returns. Required because Compose/Skiko initialisation indirectly touches
 * AWT, which spawns the non-daemon EDT, and that thread keeps the JVM alive
 * long after the Tao loop has shut down. Mirrors Compose Desktop's
 * `application { … }` (which also force-exits the process).
 */
@OptIn(ExperimentalFoundationApi::class)
public fun taoApplication(content: @Composable ApplicationScope.() -> Unit) {
    check(NativeTaoBridge.isLoaded) {
        "nucleus_tao native library is not available — supported targets: " +
            "macOS (arm64/x86_64), Windows (x64/aarch64), Linux (x64/aarch64)."
    }

    TaoApplication.run { app ->
        val scope = ComposableApplicationScope(app)
        // CoroutineScope pinned to the Tao main thread. Every `launch` posts
        // the block via TaoMainDispatcher, which queues onto the Tao event
        // loop and runs at the next `MainEventsCleared` pump tick.
        val coroutineScope =
            CoroutineScope(
                TaoMainDispatcher + TaoFrameClock + SupervisorJob(),
            )

        // Snapshot apply observer: forwards state writes from any thread back
        // to the main thread so the Recomposer wakes up. Mirrors CMP's
        // internal `GlobalSnapshotManager.ensureStarted()`.
        startGlobalSnapshotManager(coroutineScope)
        val recomposer = Recomposer(coroutineScope.coroutineContext)
        val composition = Composition(NoOpApplier, recomposer)

        coroutineScope.launch { recomposer.runRecomposeAndApplyChanges() }

        coroutineScope.launch {
            try {
                composition.setContent {
                    // Install root-level defaults for locals that Compose APIs may consult
                    // before any window scene is mounted (e.g. compose-resources `Font(…)`
                    // reads `LocalDensity.current` via `rememberEnvironment`). Mirrors the
                    // exact pattern Compose Desktop's AWT `application { }` uses — see
                    // `androidx.compose.ui.window.LayoutConfiguration.desktop.kt`. Per-window
                    // ComposeScenes override these with their own platform-correct values.
                    CompositionLocalProvider(
                        LocalDensity provides GlobalDensity,
                        LocalLayoutDirection provides GlobalLayoutDirection,
                    ) {
                        if (scope.isOpen) scope.content()
                    }
                }
                // Suspend until exitApplication() flips isOpen → false. A busy
                // `while (scope.isOpen) yield()` here re-dispatches this coroutine
                // onto TaoMainDispatcher every frame, so the pump queue is never
                // empty and the event loop spins (runaway re-dispatch). snapshotFlow
                // observes the snapshot-state write and resumes exactly once.
                snapshotFlow { scope.isOpen }.first { !it }
                recomposer.close()
                recomposer.join()
            } finally {
                composition.dispose()
                app.exit()
            }
        }
        // Return → Tao event loop continues. Pumps fire MAIN_EVENTS_CLEARED
        // and the Compose machinery resumes between platform events.
    }
    exitProcess(0)
}

/**
 * Frame clock that yields control back to the dispatcher between frames so
 * Tao events can be pumped. Mirrors Compose Desktop's `YieldFrameClock`.
 */
private object TaoFrameClock : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        kotlinx.coroutines.yield()
        return onFrame(System.nanoTime())
    }
}

private val snapshotStarted = AtomicBoolean(false)

private fun startGlobalSnapshotManager(scope: CoroutineScope) {
    if (!snapshotStarted.compareAndSet(false, true)) return
    val channel = Channel<Unit>(Channel.CONFLATED)
    val sent = AtomicBoolean(false)
    scope.launch {
        channel.consumeAsFlow().collect {
            try {
                Snapshot.sendApplyNotifications()
            } catch (ignored: Throwable) {
                // catch and ignore runtime errors to prevent application deadlock
            } finally {
                sent.set(false)
            }
        }
    }
    Snapshot.registerGlobalWriteObserver {
        if (sent.compareAndSet(false, true)) {
            channel.trySend(Unit)
        }
    }
}

private object NoOpApplier : Applier<Unit> {
    override val current: Unit = Unit

    override fun down(node: Unit) = Unit

    override fun up() = Unit

    override fun insertTopDown(
        index: Int,
        instance: Unit,
    ) = Unit

    override fun insertBottomUp(
        index: Int,
        instance: Unit,
    ) = Unit

    override fun remove(
        index: Int,
        count: Int,
    ) = Unit

    override fun move(
        from: Int,
        to: Int,
        count: Int,
    ) = Unit

    override fun clear() = Unit

    override fun onEndChanges() = Unit
}
