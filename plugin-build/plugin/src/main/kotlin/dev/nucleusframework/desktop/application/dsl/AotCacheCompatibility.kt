package dev.nucleusframework.desktop.application.dsl

/**
 * Portability profile of the generated JDK 25+ AOT cache.
 *
 * The AOT cache is not pure metadata: since JDK 25 it also stores generated machine code
 * (i2c/c2i `AdapterBlob`s) emitted for the CPU features detected on the machine that ran the
 * training. Shipping such a cache to a machine with a narrower instruction set crashes with
 * `EXCEPTION_ILLEGAL_INSTRUCTION` / `SIGILL` inside `~AdapterBlob` (issue #400).
 */
enum class AotCacheCompatibility {
    /**
     * Stores class metadata only — no cached machine code. The cache runs on any CPU of the
     * target architecture, which is what a redistributed desktop application needs, and the JIT
     * is left untouched at runtime. The default.
     */
    COMPATIBILITY,

    /**
     * Keeps the JDK default behaviour, including cached adapter code (~6% faster startup per
     * JDK-8350209), but the cache is then only valid on CPUs that support every instruction-set
     * extension of the build machine. Neither JDK 25 nor JDK 26 validates CPU features when
     * loading the code region, so a mismatch crashes instead of being rejected; the check only
     * landed after JDK 26. Use for locally-built or fleet-homogeneous deployments only.
     */
    NATIVE,
}
