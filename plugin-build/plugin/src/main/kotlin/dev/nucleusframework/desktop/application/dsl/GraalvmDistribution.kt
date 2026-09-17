package dev.nucleusframework.desktop.application.dsl

/**
 * Which GraalVM distribution the auto-downloaded toolchain uses
 * (see [GraalvmToolchainSettings]).
 *
 * The two builds are functionally equivalent for a standard native image; they differ in
 * licensing and in a handful of Oracle-only optimizations:
 * - **[COMMUNITY]** — GraalVM Community Edition, published by Oracle on GitHub under
 *   GPLv2 with the Classpath Exception. Redistributing it (and the libraries the plugin
 *   copies next to the binary) inside a paid application carries no fee restriction.
 *   This is the default.
 * - **[ORACLE]** — Oracle GraalVM (the former Enterprise Edition), distributed under the
 *   GraalVM Free Terms and Conditions (GFTC). Adds PGO (`--pgo`), `-O3`, ML-inferred
 *   profiles and `-H:AdvancedObfuscation`. The GFTC permits production and commercial use,
 *   but only allows redistribution of the Program "provided that You do not charge Your
 *   licensees any fees associated with such distribution or use of the Program" — and the
 *   plugin ships GraalVM runtime libraries (`libjvm`, `libawt`, …) next to the executable.
 *   Selecting it therefore logs a warning; review the GFTC before shipping a paid app.
 */
enum class GraalvmDistribution(
    internal val label: String,
) {
    /** GraalVM Community Edition (GPLv2 + Classpath Exception). The default. */
    COMMUNITY("GraalVM Community Edition"),

    /** Oracle GraalVM, former Enterprise Edition (GraalVM Free Terms and Conditions). */
    ORACLE("Oracle GraalVM"),
    ;

    internal val isOracle: Boolean get() = this == ORACLE
}
