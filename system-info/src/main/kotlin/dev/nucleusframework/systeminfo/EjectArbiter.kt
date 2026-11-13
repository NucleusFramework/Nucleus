package dev.nucleusframework.systeminfo

import dev.nucleusframework.systeminfo.model.DiskInfo
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.logging.Level
import java.util.logging.Logger

// macOS gives up on an approval after 10 s and then stops asking the session; Windows
// shows the user a frozen "Eject" for as long as we hold the request.
internal const val EJECT_BUDGET_MS = 3_000L

/**
 * Collects the app's eject-request handlers and answers the OS for them. The native
 * approval hook is registered while at least one handler exists.
 *
 * [decide] runs on the native callback thread, which must answer the OS quickly: the
 * handlers run on a worker thread within [budgetMs], and a handler that is too slow or
 * throws lets the eject proceed.
 */
internal class EjectArbiter(
    private val start: () -> Boolean,
    private val stop: () -> Unit,
    private val budgetMs: Long = EJECT_BUDGET_MS,
) {
    private val lock = Any()
    private val handlers = CopyOnWriteArrayList<(DiskInfo) -> Boolean>()
    private val worker =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "nucleus-eject-request").apply { isDaemon = true }
        }

    fun register(handler: (DiskInfo) -> Boolean): AutoCloseable {
        synchronized(lock) {
            if (handlers.isEmpty() && !start()) logger.fine("No native eject approval hook on this platform")
            handlers += handler
        }
        return AutoCloseable {
            synchronized(lock) {
                if (handlers.remove(handler) && handlers.isEmpty()) stop()
            }
        }
    }

    /** Returns `true` to let the eject proceed. */
    fun decide(disk: DiskInfo): Boolean {
        val pending = handlers.toList()
        if (pending.isEmpty()) return true
        val verdict = worker.submit<Boolean> { pending.all { it(disk) } }
        return try {
            verdict.get(budgetMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            verdict.cancel(true)
            logger.warning("Eject request handler for ${disk.mountPoint} took over $budgetMs ms; allowing the eject")
            true
        } catch (e: ExecutionException) {
            logger.log(
                Level.WARNING,
                "Eject request handler for ${disk.mountPoint} failed; allowing the eject",
                e.cause,
            )
            true
        }
    }

    private companion object {
        val logger: Logger = Logger.getLogger(EjectArbiter::class.java.name)
    }
}
