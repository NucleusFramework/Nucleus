package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.GarbageCollector

internal const val OPTIMIZED_XMS = "-Xms32m"
internal const val OPTIMIZED_MAX_RAM_PERCENTAGE = "-XX:MaxRAMPercentage=25"

/** Runtime flag read by `nucleus-application` to arm idle GC. Keep in sync with `NucleusOptimization`. */
internal const val NUCLEUS_IDLE_GC_PROPERTY = "nucleus.optimization.idleGc"
internal const val OPTIMIZED_IDLE_GC_FLAG = "-D$NUCLEUS_IDLE_GC_PROPERTY=true"

internal val JvmApplicationData.optSerialGc: Boolean
    get() = nucleusOptimizationSettings.serialGc ?: nucleusOptimization

internal val JvmApplicationData.optCompactHeap: Boolean
    get() = nucleusOptimizationSettings.compactHeap ?: nucleusOptimization

internal val JvmApplicationData.optSingleJar: Boolean
    get() = nucleusOptimizationSettings.singleJar ?: nucleusOptimization

internal val JvmApplicationData.optIdleGc: Boolean
    get() = nucleusOptimizationSettings.idleGc ?: nucleusOptimization

/**
 * Applies [JvmApplicationData.nucleusOptimization] JVM flags without clobbering an
 * explicit collector or heap flags already on [app].
 */
internal fun applyNucleusOptimization(app: JvmApplicationData) {
    if (app.optSerialGc && app.garbageCollector == null) {
        app.garbageCollector = GarbageCollector.SERIAL
    }
    if (app.optCompactHeap) {
        if (app.jvmArgs.none { it.startsWith("-Xms") }) {
            app.jvmArgs.add(OPTIMIZED_XMS)
        }
        if (app.jvmArgs.none { it.startsWith("-XX:MaxRAMPercentage") }) {
            app.jvmArgs.add(OPTIMIZED_MAX_RAM_PERCENTAGE)
        }
    }
    if (app.optIdleGc && app.jvmArgs.none { it.startsWith("-D$NUCLEUS_IDLE_GC_PROPERTY=") }) {
        app.jvmArgs.add(OPTIMIZED_IDLE_GC_FLAG)
    }
}
