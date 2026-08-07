package dev.nucleusframework.application

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Schedules an automatic exit after [duration] when the JVM is in AOT training
 * mode (`-Dnucleus.aot.mode=training`). No-op otherwise.
 *
 * The [onTimeout] lambda runs on a non-daemon timer thread once the duration
 * elapses. The default calls [exitApplication] so the Tao/Compose event loop
 * exits cleanly before the JVM shuts down. This is required for JDK 25 AOT
 * cache generation: the cache writer needs all threads at a safepoint, which
 * cannot happen if the main thread is still blocked inside the native event
 * loop. After [exitApplication] the event loop unwinds, the main thread ends,
 * and the JVM shuts down naturally — running shutdown hooks including the AOT
 * cache writer.
 *
 * Safe to call multiple times — only the first invocation per process arms
 * the timer.
 *
 * ```
 * nucleusApplication(args) {
 *     aotTraining(duration = 45.seconds)
 *     onDeepLink { … }
 *     …
 * }
 * ```
 */
public fun NucleusApplicationScope.aotTraining(
    duration: Duration = 15.seconds,
    onTimeout: NucleusApplicationScope.() -> Unit = { exitApplication() },
) {
    if (!isAotTraining) return
    if (!aotTrainingArmed.compareAndSet(false, true)) return

    println("[AOT] Training mode — will exit in $duration")

    Thread({
        Thread.sleep(duration.inWholeMilliseconds)
        println("[AOT] Time's up, exiting…")
        onTimeout()
    }, "nucleus-aot-training-timer").apply {
        isDaemon = false
        start()
    }
}

private val aotTrainingArmed = AtomicBoolean(false)
