package dev.nucleusframework.desktop.application.dsl

/**
 * Target CPU instruction-set architecture passed to GraalVM `native-image` (the `-march` flag).
 *
 * Leave [dev.nucleusframework.desktop.application.dsl.GraalvmSettings.march] unset to use the
 * per-platform default: [COMPATIBILITY] everywhere except macOS on Apple Silicon, which defaults
 * to [NATIVE] (its baseline `armv8-a` is already present on every supported Mac, so there is no
 * portability cost).
 */
enum class NativeImageMarch(
    internal val flag: String,
) {
    /**
     * `-march=native`: optimize for the exact CPU of the build machine. Produces the fastest
     * binary but it crashes on any older/different CPU ("does not support all of the following
     * CPU features"). Use only for locally-run builds, never for a distributed x86-64 binary.
     */
    NATIVE("native"),

    /**
     * `-march=compatibility`: the lowest-common-denominator ISA baseline (`x86-64-v1` / `armv8-a`).
     * The produced binary runs on any CPU of the target architecture — the safe default for
     * distributed binaries.
     */
    COMPATIBILITY("compatibility"),
}
