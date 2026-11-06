package dev.nucleusframework.lab.core.time

/** Monotonic milliseconds, for gaps and durations (never for display times). */
fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

/** Runs [block] and returns its result with how long it took, in milliseconds. */
inline fun <T> timedMillis(block: () -> T): Pair<T, Long> {
    val started = System.nanoTime()
    val result = block()
    return result to (System.nanoTime() - started) / NANOS_PER_MILLI
}

const val NANOS_PER_MILLI: Long = 1_000_000
