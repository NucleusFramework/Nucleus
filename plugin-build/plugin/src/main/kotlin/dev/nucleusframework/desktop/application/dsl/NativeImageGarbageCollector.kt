package dev.nucleusframework.desktop.application.dsl

/**
 * Garbage collector baked into a GraalVM native image (the `--gc=` flag).
 *
 * ```kotlin
 * nucleus.application {
 *     graalvm {
 *         garbageCollector = NativeImageGarbageCollector.G1
 *     }
 * }
 * ```
 *
 * Unlike the JVM, the collector is chosen at build time and cannot be switched at runtime. Leave
 * [GraalvmSettings.garbageCollector] unset to keep native-image's own default ([SERIAL]).
 *
 * A collector unavailable on the resolved toolchain or platform ([isOracleOnly], [isLinuxOnly])
 * degrades to a warning and the Serial GC instead of failing the build, so the same repository
 * still builds everywhere.
 */
enum class NativeImageGarbageCollector(
    internal val id: String,
    internal val maxHeapPercentOption: String,
    internal val isOracleOnly: Boolean = false,
    internal val isLinuxOnly: Boolean = false,
) {
    /**
     * `--gc=serial`: native-image's default. Single-threaded generational collector tuned for the
     * small heaps a desktop app lives on; pauses grow with the heap.
     */
    SERIAL("serial", maxHeapPercentOption = "MaximumHeapSizePercent"),

    /**
     * `--gc=G1`: the HotSpot G1 collector, for apps whose heap outgrows what [SERIAL] can collect
     * without visible pauses (roughly > 1–2 GB). Trades a larger image and a slower startup for
     * much shorter pauses under load.
     *
     * Oracle GraalVM on Linux (AMD64/AArch64) only — GraalVM Community Edition, Liberica NIK and
     * Mandrel reject `--gc=G1`, as do the macOS and Windows builds.
     */
    G1("G1", maxHeapPercentOption = "MaxRAMPercentage", isOracleOnly = true, isLinuxOnly = true),

    /**
     * `--gc=epsilon`: allocates and never reclaims — the image dies with `OutOfMemoryError` once
     * the heap is full. For short-lived, allocation-bounded processes (CLI one-shots, benchmarks),
     * never for a long-running UI.
     */
    EPSILON("epsilon", maxHeapPercentOption = "MaximumHeapSizePercent"),
    ;

    /** The `native-image` flag selecting this collector. */
    internal val flag: String get() = "--gc=$id"
}
