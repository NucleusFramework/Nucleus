package dev.nucleusframework.desktop.application.dsl

/**
 * JDK 25+ AOT cache settings, scoped under `nativeDistributions { aotCache { ... } }`.
 *
 * ```kotlin
 * nativeDistributions {
 *     aotCache {
 *         enabled = true
 *         // default: portable cache, runs on any CPU
 *         // compatibility = AotCacheCompatibility.NATIVE
 *     }
 * }
 * ```
 */
abstract class AotCacheSettings {
    /** Generates an AOT cache during packaging and wires `-XX:AOTCache` into the launcher. */
    var enabled: Boolean = false

    /** Portability profile of the generated cache. See [AotCacheCompatibility]. */
    var compatibility: AotCacheCompatibility = AotCacheCompatibility.COMPATIBILITY

    /** Extra JVM arguments passed to the training run only (escape hatch). */
    val extraTrainingJvmArgs: MutableList<String> = mutableListOf()

    /** Adds one or more arguments to [extraTrainingJvmArgs]. */
    fun extraTrainingJvmArgs(vararg args: String) {
        extraTrainingJvmArgs.addAll(args.toList())
    }
}
