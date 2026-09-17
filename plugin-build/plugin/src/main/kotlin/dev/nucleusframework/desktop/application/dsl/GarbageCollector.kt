package dev.nucleusframework.desktop.application.dsl

/**
 * HotSpot garbage collector used by the JVM distribution.
 *
 * ```kotlin
 * nucleus.application {
 *     garbageCollector = GarbageCollector.Z
 * }
 * ```
 *
 * The flags are prepended to the launcher's JVM arguments (and to the `run` task), so an explicit
 * `-XX:+Use…GC` in [JvmApplication.jvmArgs] still wins — HotSpot honors the last collector flag.
 * Leave [JvmApplication.garbageCollector] unset to keep the JDK's own ergonomics-based choice
 * ([G1] on any desktop-class machine, [SERIAL] on very small containers).
 *
 * The selected collector must exist in the JDK the app ships with: the launcher aborts at startup
 * with `Unrecognized VM option` otherwise. Every entry below is present in mainstream JDK 17+
 * builds (Temurin, Corretto, Zulu, Liberica, GraalVM), except [SHENANDOAH], which Oracle's own JDK
 * builds do not include.
 */
enum class GarbageCollector(
    internal val jvmArgs: List<String>,
) {
    /**
     * `-XX:+UseSerialGC`: single-threaded, stop-the-world. Smallest footprint and the fastest to
     * start up, at the cost of pauses that grow with the heap. A reasonable pick for small
     * utility apps (heap well under ~1 GB) where startup time matters more than pause times.
     */
    SERIAL(listOf("-XX:+UseSerialGC")),

    /**
     * `-XX:+UseParallelGC`: multi-threaded, stop-the-world, throughput-oriented. Maximizes raw
     * work done per CPU cycle but pauses the whole app to collect — visible as dropped frames in
     * a Compose UI. Suited to compute-heavy tools, not to latency-sensitive interfaces.
     */
    PARALLEL(listOf("-XX:+UseParallelGC")),

    /**
     * `-XX:+UseG1GC`: HotSpot's default on desktop-class machines. Mostly concurrent, region-based,
     * with a pause-time goal (`-XX:MaxGCPauseMillis`, 200 ms by default). Set it explicitly to keep
     * the collector stable across machines instead of relying on JVM ergonomics.
     */
    G1(listOf("-XX:+UseG1GC")),

    /**
     * `-XX:+UseZGC`: fully concurrent, sub-millisecond pauses regardless of heap size — the best
     * fit for a UI that must never drop a frame to GC. Costs some throughput and a higher baseline
     * footprint (roughly 10–20% more RSS than [G1]). Generational since JDK 24, and therefore
     * usable with desktop-sized heaps; on JDK 21–23 add `-XX:+ZGenerational` via
     * [JvmApplication.jvmArgs], otherwise the non-generational mode wastes memory.
     */
    Z(listOf("-XX:+UseZGC")),

    /**
     * `-XX:+UseShenandoahGC`: concurrent low-pause collector with a smaller footprint than [Z] and
     * eager heap uncommitting, which keeps an idle desktop app's RSS low. Absent from Oracle JDK
     * builds — the launcher fails to start there. Safe on Temurin, Corretto, Zulu and Liberica.
     */
    SHENANDOAH(listOf("-XX:+UseShenandoahGC")),

    /**
     * `-XX:+UseEpsilonGC` (experimental): allocates and never reclaims — the app dies with
     * `OutOfMemoryError` once the heap is full. Only meaningful for short benchmark runs that
     * measure allocation cost with GC removed from the picture; never ship it.
     */
    EPSILON(listOf("-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC")),
}
