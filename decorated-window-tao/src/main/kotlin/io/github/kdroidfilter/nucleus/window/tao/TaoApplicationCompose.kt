package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Composable variant of [taoApplication]. Mirrors Compose Desktop's
 * `awaitApplication` but pumps the Compose runtime through Tao's event loop
 * via [TaoMainDispatcher] (single-threaded model — no AWT EDT).
 *
 * Inside [content] you may call `@Composable` [DecoratedWindow]s, use
 * `LaunchedEffect`/`DisposableEffect`, observe `MutableState`, etc. The
 * composition lives until [ApplicationScope.exitApplication] is called.
 */
fun taoApplicationComposable(
    content: @Composable ApplicationScope.() -> Unit,
) {
    check(NativeTaoBridge.isLoaded) {
        "nucleus_tao native library is not available — did you run on macOS arm64/x86_64?"
    }

    TaoApplication.run { app ->
        val scope = ComposableApplicationScope(app)
        // CoroutineScope pinned to the Tao main thread. Every `launch` posts
        // the block via TaoMainDispatcher, which queues onto the Tao event
        // loop and runs at the next `MainEventsCleared` pump tick.
        val coroutineScope = CoroutineScope(
            TaoMainDispatcher + TaoFrameClock + Job(),
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
                    if (scope.isOpen) scope.content()
                }
                while (scope.isOpen) yield()
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
}

/**
 * Frame clock that yields control back to the dispatcher between frames so
 * Tao events can be pumped. Mirrors Compose Desktop's `YieldFrameClock`.
 */
private object TaoFrameClock : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(
        onFrame: (frameTimeNanos: Long) -> R,
    ): R {
        kotlinx.coroutines.yield()
        return onFrame(System.nanoTime())
    }
}

private val snapshotStarted = AtomicBoolean(false)

private fun startGlobalSnapshotManager(scope: CoroutineScope) {
    if (!snapshotStarted.compareAndSet(false, true)) return
    val channel = Channel<Unit>(1)
    val sent = AtomicBoolean(false)
    scope.launch {
        channel.consumeAsFlow().collect {
            sent.set(false)
            Snapshot.sendApplyNotifications()
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
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun clear() = Unit
    override fun onEndChanges() = Unit
}

private class ComposableApplicationScope(
    override val taoApplication: TaoApplication,
) : ApplicationScope {
    var isOpen by mutableStateOf(true)
    override fun exitApplication() {
        isOpen = false
    }
}
