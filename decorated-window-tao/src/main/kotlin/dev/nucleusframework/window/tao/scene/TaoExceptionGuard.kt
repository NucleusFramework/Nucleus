@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import dev.nucleusframework.window.tao.TaoApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Runs [block], routing anything it throws to this handler — the Tao mirror of
 * Compose Desktop's `ComposeSceneMediator.catchExceptions`.
 *
 * Contract, identical to the AWT backend's: a handler that returns normally
 * swallows the failure and the calling thread carries on; a handler that
 * rethrows propagates it. A `null` handler always propagates, which is what
 * every entry point did before this existed.
 *
 * [CancellationException] is never handed to the handler: on the render path
 * the block spans coroutine suspension points, and a swallowed cancellation
 * would let an already-cancelled frame loop keep running.
 */
@Suppress("TooGenericExceptionCaught") // catching everything is the whole point of the guard
internal inline fun WindowExceptionHandler?.catchExceptions(block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        this?.onException(e) ?: throw e
    }
}

/**
 * [catchExceptions] for entry points that answer "did the scene consume this
 * event?". A swallowed failure reports [fallback] — `false` at every current
 * call site, so a key the scene failed on falls through to the app's own
 * handlers instead of being reported as consumed.
 */
@Suppress("TooGenericExceptionCaught") // see catchExceptions
internal inline fun WindowExceptionHandler?.catchExceptions(
    fallback: Boolean,
    block: () -> Boolean,
): Boolean =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        this?.onException(e) ?: throw e
        fallback
    }

/**
 * The single [CoroutineExceptionHandler] every Tao scene carries, chaining the
 * per-window handler of #621 in front of the app-fatal path of #622.
 *
 * Why a coroutine handler is needed at all: a failing composition never leaves
 * `ComposeScene.render`. `Recomposer.processCompositionError` intercepts it,
 * logs it, and rethrows into the `runCatching` inside `withFrameNanos`, so it
 * arrives here as a *coroutine* failure of the recomposition loop rather than
 * out of the frame call that [catchExceptions] wraps. The same interception is
 * why such a failure is **not survivable**: it ends
 * `Recomposer.runRecomposeAndApplyChanges`, and nothing outside
 * `androidx.compose.runtime` can restart it (`setHotReloadEnabled` /
 * `retryFailedCompositions` are internal).
 *
 * The chain, in order:
 *  1. **Teardown** ([closed] set) — a cancelled effect's `finally` throwing as
 *     its window closes is noise; log at SEVERE, never take the app down (#622).
 *  2. **The window's [handler]**, when the app installed one. Returning
 *     normally means "swallow and continue", and that is honoured — but if the
 *     scene cannot recompose any more ([sceneIsAlive]) the window is frozen, so
 *     say so at SEVERE instead of leaving it silent. Rethrowing from the
 *     handler falls through to step 3 with whatever it threw.
 *  3. **Fatal** ([onFatal] → `TaoApplication.reportFatal`): SEVERE log, native
 *     error dialog, clean loop exit. This is also what an app that installs no
 *     factory gets, since the default one rethrows.
 *
 * [handler] and [sceneIsAlive] are mutable because the scene's coroutine
 * context is built before either is known; they are written at window-open time
 * and read from whichever thread the failing coroutine ran on.
 */
internal class TaoSceneExceptionRouter(
    private val closed: AtomicBoolean,
    /** Seam for tests; production always routes to the #622 fatal path. */
    private val onFatal: (Throwable) -> Unit = TaoApplication::reportFatal,
) : AbstractCoroutineContextElement(CoroutineExceptionHandler),
    CoroutineExceptionHandler {
    @Volatile
    var handler: WindowExceptionHandler? = null

    /**
     * Reports whether the scene can still recompose. Installed by the bundle
     * factory once the bundle exists (the router has to be built first, to go
     * into the scene's coroutine context).
     */
    @Volatile
    var sceneIsAlive: () -> Boolean = { true }

    @Suppress("TooGenericExceptionCaught") // a rethrowing handler may throw anything
    override fun handleException(
        context: CoroutineContext,
        exception: Throwable,
    ) {
        if (closed.get()) {
            sceneExceptionLogger.log(Level.SEVERE, "Unhandled exception during scene teardown", exception)
            return
        }
        val handler = this.handler
        if (handler == null) {
            onFatal(exception)
            return
        }
        val rethrown =
            try {
                handler.onException(exception)
                null
            } catch (t: Throwable) {
                t
            }
        if (rethrown != null) {
            onFatal(rethrown)
            return
        }
        // The handler chose to continue. Only some coroutine failures allow
        // that (an effect launched off a live dispatcher, say); a recomposition
        // failure has already shut the recomposer down, and staying quiet here
        // is exactly how a window ends up frozen with no diagnostic. The app
        // asked to swallow, so this stops short of the fatal dialog.
        if (!sceneIsAlive()) {
            sceneExceptionLogger.log(
                Level.SEVERE,
                "The Compose recomposer did not survive this exception, so the window will no " +
                    "longer update. Compose terminates the recomposition loop on a composition " +
                    "failure and it cannot be restarted in place — close and recreate the window, " +
                    "or rethrow from the WindowExceptionHandler to take the fatal path instead.",
                exception,
            )
        }
    }
}

private val sceneExceptionLogger: Logger =
    Logger.getLogger("dev.nucleusframework.window.tao.exception")
