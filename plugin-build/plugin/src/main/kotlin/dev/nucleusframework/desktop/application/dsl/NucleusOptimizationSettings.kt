package dev.nucleusframework.desktop.application.dsl

/**
 * Per-knob overrides for [JvmApplication.nucleusOptimization].
 *
 * `null` (the default) follows the master boolean. `true` / `false` force that
 * knob on or off, independently of the master and of the other knobs.
 *
 * Does not cover AOT ([JvmApplicationDistributions.enableAotCache]) or ProGuard.
 *
 * ```
 * nucleus.application {
 *     nucleusOptimization = true
 *     nucleusOptimization { idleGc = false }
 * }
 *
 * nucleus.application {
 *     nucleusOptimization { singleJar = true }
 * }
 * ```
 */
abstract class NucleusOptimizationSettings {
    /**
     * Serial GC when [JvmApplication.garbageCollector] is unset.
     * An explicit collector always wins.
     */
    var serialGc: Boolean? = null

    /**
     * `-Xms32m` and `-XX:MaxRAMPercentage=25`, unless already present in
     * [JvmApplication.jvmArgs].
     */
    var compactHeap: Boolean? = null

    /**
     * Flatten runtime JARs (or [ProguardSettings.joinOutputJars] when ProGuard
     * is on) so the jpackage image contains a single JAR.
     */
    var singleJar: Boolean? = null

    /**
     * Request a GC 3s after the last window loses focus, or immediately when a
     * window is minimized.
     */
    var idleGc: Boolean? = null

    /**
     * Package and run the app with the current OpenJDK feature release,
     * auto-downloaded and cached under `<gradle-user-home>/nucleus/jdk` like
     * the GraalVM toolchain. An explicit [JvmApplication.javaHome] always
     * wins. Does not change the Gradle compile JDK.
     */
    var lastJdk: Boolean? = null
}
