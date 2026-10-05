package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.GarbageCollector
import org.gradle.api.Project

internal const val OPTIMIZED_XMS = "-Xms32m"
internal const val OPTIMIZED_MAX_RAM_PERCENTAGE = "-XX:MaxRAMPercentage=25"

/**
 * Runtime flag read by `nucleus-application` to arm idle GC. Keep in sync with `NucleusOptimization`.
 * Also baked into `nucleus-app.properties` as [NUCLEUS_IDLE_GC_RESOURCE_KEY], since a native image
 * has no launcher `.cfg` to carry the `-D`.
 */
internal const val NUCLEUS_IDLE_GC_PROPERTY = "nucleus.optimization.idleGc"
internal const val OPTIMIZED_IDLE_GC_FLAG = "-D$NUCLEUS_IDLE_GC_PROPERTY=true"
internal const val NUCLEUS_IDLE_GC_RESOURCE_KEY = "optimization.idleGc"

/**
 * Runtime flag read by `decorated-window-tao` (`PartialRedraw`) to turn partial redraw on.
 * Also baked into `nucleus-app.properties` as [NUCLEUS_PARTIAL_REDRAW_RESOURCE_KEY], for
 * native images.
 */
internal const val NUCLEUS_PARTIAL_REDRAW_PROPERTY = "nucleus.tao.partialRedraw"
internal const val OPTIMIZED_PARTIAL_REDRAW_FLAG = "-D$NUCLEUS_PARTIAL_REDRAW_PROPERTY=true"
internal const val NUCLEUS_PARTIAL_REDRAW_RESOURCE_KEY = "optimization.partialRedraw"

/**
 * Runtime flag read by `linux-hidpi` (`capLinuxMallocArenas`) to cap glibc's malloc arenas.
 * Also baked into `nucleus-app.properties` as [NUCLEUS_MALLOC_ARENA_MAX_RESOURCE_KEY], for
 * native images.
 */
internal const val NUCLEUS_MALLOC_ARENA_MAX_PROPERTY = "nucleus.optimization.mallocArenaMax"
internal const val OPTIMIZED_MALLOC_ARENA_MAX = 2
internal const val OPTIMIZED_MALLOC_ARENA_MAX_FLAG = "-D$NUCLEUS_MALLOC_ARENA_MAX_PROPERTY=$OPTIMIZED_MALLOC_ARENA_MAX"
internal const val NUCLEUS_MALLOC_ARENA_MAX_RESOURCE_KEY = "optimization.mallocArenaMax"

internal val JvmApplicationData.optSerialGc: Boolean
    get() = nucleusOptimizationSettings.serialGc ?: nucleusOptimization

internal val JvmApplicationData.optCompactHeap: Boolean
    get() = nucleusOptimizationSettings.compactHeap ?: nucleusOptimization

internal val JvmApplicationData.optSingleJar: Boolean
    get() = nucleusOptimizationSettings.singleJar ?: nucleusOptimization

internal val JvmApplicationData.optIdleGc: Boolean
    get() = nucleusOptimizationSettings.idleGc ?: nucleusOptimization

internal val JvmApplicationData.optLastJdk: Boolean
    get() = nucleusOptimizationSettings.lastJdk ?: nucleusOptimization

internal val JvmApplicationData.optPartialRedraw: Boolean
    get() = nucleusOptimizationSettings.partialRedraw ?: nucleusOptimization

internal val JvmApplicationData.optMallocArenas: Boolean
    get() = nucleusOptimizationSettings.mallocArenas ?: nucleusOptimization

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
    if (app.optPartialRedraw && app.jvmArgs.none { it.startsWith("-D$NUCLEUS_PARTIAL_REDRAW_PROPERTY=") }) {
        app.jvmArgs.add(OPTIMIZED_PARTIAL_REDRAW_FLAG)
    }
    if (app.optMallocArenas && app.jvmArgs.none { it.startsWith("-D$NUCLEUS_MALLOC_ARENA_MAX_PROPERTY=") }) {
        app.jvmArgs.add(OPTIMIZED_MALLOC_ARENA_MAX_FLAG)
    }
}

/**
 * Points packaging / `run` at an auto-downloaded current OpenJDK when
 * [JvmApplicationData.optLastJdk] is on. An explicit `javaHome` wins. The
 * [org.gradle.api.provider.ValueSource] stays lazy — listing tasks does not
 * download the JDK.
 */
internal fun applyNucleusOptimizationJdk(
    project: Project,
    app: JvmApplicationData,
) {
    if (!app.optLastJdk || app.hasCustomJavaHome || app.javaHomeOverride != null) return
    app.javaHomeOverride =
        project.providers.of(NucleusJdkToolchainValueSource::class.java) { spec ->
            spec.parameters.installBaseDir.set(
                project.gradle.gradleUserHomeDir.resolve("nucleus/jdk").absolutePath,
            )
        }
}
