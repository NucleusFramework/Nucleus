package dev.nucleusframework.updater.internal

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Closes [input] once it has gone [timeout] without delivering any data, which ends a read blocked
 * on it with an [IOException]; [stalled] then tells that failure apart from any other.
 *
 * Nothing else bounds reading an HTTP response body: a request timeout covers only the headers, so
 * a server that stops sending mid-body would otherwise hang the read forever. Closing the JDK
 * `HttpClient`'s `ofInputStream` body from another thread wakes a reader blocked on it, from JDK 11
 * on. [close] cancels the watch, so a stream that is closed normally costs nothing more.
 */
internal class StallGuardedInputStream(
    input: InputStream,
    private val timeout: Duration,
) : FilterInputStream(input) {
    @Volatile
    private var lastData = System.nanoTime()

    @Volatile
    private var closed = false

    /** True once the stream was closed because no data arrived for [timeout]. */
    @Volatile
    var stalled = false
        private set

    private var check: ScheduledFuture<*> = schedule(timeout.toNanos())

    override fun read(): Int = super.read().also { if (it != -1) lastData = System.nanoTime() }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = super.read(b, off, len).also { if (it > 0) lastData = System.nanoTime() }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            check.cancel(false)
        }
        super.close()
    }

    private fun schedule(delayNanos: Long): ScheduledFuture<*> =
        SCHEDULER.schedule(::checkIdle, delayNanos, TimeUnit.NANOSECONDS)

    private fun checkIdle() {
        val idle = System.nanoTime() - lastData
        synchronized(this) {
            if (closed) return
            if (idle < timeout.toNanos()) {
                check = schedule(timeout.toNanos() - idle)
                return
            }
            stalled = true
        }
        runCatching { close() }
    }

    private companion object {
        /** One daemon thread serves every guarded stream: each check only reads a timestamp. */
        val SCHEDULER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "nucleus-updater-stall-guard").apply { isDaemon = true }
            }
    }
}
