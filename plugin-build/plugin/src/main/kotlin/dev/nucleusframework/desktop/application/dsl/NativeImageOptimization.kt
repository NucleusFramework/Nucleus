package dev.nucleusframework.desktop.application.dsl

/**
 * Optimization level passed to GraalVM `native-image` (the `-O*` flag).
 *
 * Only one `-O*` flag is honored by native-image (the last one wins); this enum maps to exactly one.
 * Leave [dev.nucleusframework.desktop.application.dsl.GraalvmSettings.optimization] unset to keep
 * native-image's own default ([LEVEL_2]).
 */
enum class NativeImageOptimization(
    internal val flag: String,
) {
    /** `-Ob`: quick build mode. Fastest compilation, minimal optimization — for local dev iteration. */
    QUICK_BUILD("-Ob"),

    /** `-O0`: no optimizations. */
    NONE("-O0"),

    /** `-O1`: basic optimizations. */
    LEVEL_1("-O1"),

    /** `-O2`: native-image's default — balanced optimization for speed. */
    LEVEL_2("-O2"),

    /** `-O3`: aggressive optimizations for peak runtime performance. Oracle GraalVM only. */
    LEVEL_3("-O3"),

    /**
     * `-Os`: optimize for binary size. Typically trims 20–30% off Compose images at a negligible
     * runtime cost for desktop apps (most work happens in Skiko's native code). Also disables Oracle
     * GraalVM's ML-inferred PGO as a side effect (the ML pass only runs at `-O2`/`-O3`).
     */
    SIZE("-Os"),
}
