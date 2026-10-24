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
 * A collector unavailable on the resolved toolchain or platform ([isOracleOnly],
 * [nonLinuxMinVersion]) degrades to a warning and the Serial GC instead of failing the build, so
 * the same repository still builds everywhere.
 */
enum class NativeImageGarbageCollector(
    internal val id: String,
    internal val maxHeapPercentOption: String,
    internal val isOracleOnly: Boolean = false,
    internal val nonLinuxMinVersion: String? = null,
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
     * Oracle GraalVM only: GraalVM Community Edition, Liberica NIK and Mandrel ship no G1 at all
     * and fail the build with `Invalid option '--gc'. 'G1' is not an accepted value`. Linux
     * (AMD64/AArch64) has it since 25.0; macOS and Windows only since **25.4** — 25.3 advertises
     * `--gc=G1` and ships the header but not the static library, so the build dies at link time
     * with `LNK1181: cannot open input file 'g1gc-cr.lib'`.
     */
    G1(
        "G1",
        maxHeapPercentOption = "MaxRAMPercentage",
        isOracleOnly = true,
        nonLinuxMinVersion = "25.4",
    ),

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
