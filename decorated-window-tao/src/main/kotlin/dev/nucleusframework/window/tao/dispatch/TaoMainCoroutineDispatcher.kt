package dev.nucleusframework.window.tao.dispatch

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.Runnable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * `Dispatchers.Main` implementation for the Tao backend.
 *
 * Routes coroutine dispatches onto the Tao main thread via [TaoMainDispatcher],
 * which is drained on every `MAIN_EVENTS_CLEARED` native event tick. The
 * "main thread" predicate is intentionally strict: only the captured Tao main
 * thread is considered already-on-Main. The AWT EDT is **not** treated as a
 * Main alias — under the Tao backend the EDT may not be running at all, and
 * conflating the two leads to silent state splits and false negatives in
 * AndroidX Lifecycle's thread checker.
 *
 * Implements [Delay] (via a daemon [ScheduledExecutorService] that re-posts
 * resume callbacks through [dispatch]) so that `DefaultDelay` routes all
 * process-wide `delay()` calls back onto the Tao main thread instead of
 * silently parking them when the Tao runtime is the resolved
 * `Dispatchers.Main`. Without this, `Dispatchers.Main as Delay` would crash or
 * stall every `delay`/`withTimeout` in the process — including Compose
 * runtime, RepeatOnLifecycle, animation timers, and Ktor request timeouts.
 *
 * Discovered through [TaoMainDispatcherFactory] (`loadPriority = 100`) via the
 * standard `META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory`
 * service loader, mirroring `kotlinx-coroutines-swing`.
 */
@OptIn(InternalCoroutinesApi::class)
internal sealed class TaoMainCoroutineDispatcher :
    MainCoroutineDispatcher(),
    Delay {
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        TaoMainDispatcher.dispatch(context, block)
    }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean {
        val taoMain = TaoMainDispatcher.taoMainThread
        return taoMain == null || Thread.currentThread() !== taoMain
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val future =
            DelayScheduler.schedule(
                {
                    // Resume on the Tao main thread, undispatched, so the
                    // resumed continuation runs synchronously in the next
                    // pump tick rather than re-entering the dispatcher.
                    dispatch(
                        continuation.context,
                        Runnable { with(continuation) { resumeUndispatched(Unit) } },
                    )
                },
                timeMillis,
                TimeUnit.MILLISECONDS,
            )
        continuation.invokeOnCancellation { future.cancel(false) }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle {
        val future =
            DelayScheduler.schedule(
                { dispatch(context, block) },
                timeMillis,
                TimeUnit.MILLISECONDS,
            )
        return DisposableHandle { future.cancel(false) }
    }

    override fun toString(): String = "Dispatchers.Main[Tao]"
}

internal object ImmediateTaoMainDispatcher : TaoMainCoroutineDispatcher() {
    override val immediate: MainCoroutineDispatcher get() = this
}

/**
 * Shared scheduler for [Delay] callbacks. Single daemon thread — Tao's own
 * native timer source isn't exposed at the JVM level, so we rely on a small
 * background scheduler that re-posts the timer firing back into the Tao main
 * thread via [TaoMainCoroutineDispatcher.dispatch]. The scheduler thread
 * itself only schedules — it never runs user code.
 */
internal object DelayScheduler {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Nucleus-Tao-Delay").apply { isDaemon = true }
        }

    fun schedule(
        command: Runnable,
        delay: Long,
        unit: TimeUnit,
    ) = executor.schedule(command, delay, unit)
}
