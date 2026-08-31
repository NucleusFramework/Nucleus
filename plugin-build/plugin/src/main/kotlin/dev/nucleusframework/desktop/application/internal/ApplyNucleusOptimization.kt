package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.GarbageCollector

internal const val OPTIMIZED_XMS = "-Xms32m"
internal const val OPTIMIZED_MAX_RAM_PERCENTAGE = "-XX:MaxRAMPercentage=25"

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
}
