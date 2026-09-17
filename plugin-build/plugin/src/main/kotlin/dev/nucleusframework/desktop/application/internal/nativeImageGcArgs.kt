package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.NativeImageGarbageCollector

/**
 * A requested native-image garbage collector checked against the resolved toolchain and platform.
 *
 * [gc] is `null` when nothing was requested, or when the request was dropped — in which case
 * [warning] explains why and native-image falls back to its default Serial GC.
 */
internal data class NativeImageGcResolution(
    val gc: NativeImageGarbageCollector?,
    val warning: String?,
)

/**
 * Drops a garbage collector the current toolchain or platform cannot build with, so a project
 * pinning `--gc=G1` still builds on GraalVM CE, macOS and Windows (with a warning) instead of
 * failing on an unknown native-image option.
 */
internal fun resolveNativeImageGc(
    requested: NativeImageGarbageCollector?,
    isOracleGraalvm: Boolean,
    isLinux: Boolean,
    graalvmHome: String,
): NativeImageGcResolution {
    if (requested == null) return NativeImageGcResolution(gc = null, warning = null)

    val unsupportedReason =
        when {
            requested.isOracleOnly && !isOracleGraalvm ->
                "${requested.flag} requires Oracle GraalVM (current toolchain: $graalvmHome)"
            requested.isLinuxOnly && !isLinux ->
                "${requested.flag} is only supported on Linux"
            else -> return NativeImageGcResolution(gc = requested, warning = null)
        }

    return NativeImageGcResolution(
        gc = null,
        warning =
            "Garbage collector ${requested.name} ignored — $unsupportedReason. " +
                "Falling back to the Serial GC.",
    )
}

/**
 * Builds the collector selection and the baked default heap ceiling.
 *
 * The heap options are collector-specific: Serial and Epsilon size the heap from
 * `MaximumHeapSizePercent`, G1 from `MaxRAMPercentage`. An absolute [maxHeapSize] applies to all
 * three and takes precedence over [maxHeapSizePercent]. Everything is baked as a default only,
 * still overridable at runtime with `-Xmx`.
 */
internal fun nativeImageGcArgs(
    gc: NativeImageGarbageCollector?,
    maxHeapSize: String?,
    maxHeapSizePercent: Int,
): List<String> =
    buildList {
        if (gc != null) {
            add(gc.flag)
        }
        if (maxHeapSize != null) {
            add("-R:MaxHeapSize=$maxHeapSize")
        } else {
            val percentOption = (gc ?: NativeImageGarbageCollector.SERIAL).maxHeapPercentOption
            add("-R:$percentOption=$maxHeapSizePercent")
        }
    }
