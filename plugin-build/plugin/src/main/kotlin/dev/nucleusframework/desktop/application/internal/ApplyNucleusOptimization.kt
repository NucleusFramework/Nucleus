package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.GarbageCollector

internal const val OPTIMIZED_XMS = "-Xms32m"
internal const val OPTIMIZED_MAX_RAM_PERCENTAGE = "-XX:MaxRAMPercentage=25"

/** Runtime flag read by `nucleus-application` to arm idle GC. Keep in sync with `NucleusOptimization`. */
internal const val NUCLEUS_OPTIMIZATION_PROPERTY = "nucleus.optimization"
internal const val OPTIMIZED_RUNTIME_FLAG = "-D$NUCLEUS_OPTIMIZATION_PROPERTY=true"

/**
 * Applies [JvmApplicationData.nucleusOptimization] JVM flags without clobbering an
 * explicit collector or heap flags already on [app].
 */
internal fun applyNucleusOptimization(app: JvmApplicationData) {
    if (!app.nucleusOptimization) return
    if (app.garbageCollector == null) {
        app.garbageCollector = GarbageCollector.SERIAL
    }
    if (app.jvmArgs.none { it.startsWith("-Xms") }) {
        app.jvmArgs.add(OPTIMIZED_XMS)
    }
    if (app.jvmArgs.none { it.startsWith("-XX:MaxRAMPercentage") }) {
        app.jvmArgs.add(OPTIMIZED_MAX_RAM_PERCENTAGE)
    }
    if (app.jvmArgs.none { it.startsWith("-D$NUCLEUS_OPTIMIZATION_PROPERTY=") }) {
        app.jvmArgs.add(OPTIMIZED_RUNTIME_FLAG)
    }
}
