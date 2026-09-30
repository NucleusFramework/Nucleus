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
 * pinning `--gc=G1` still builds on GraalVM CE, or on a macOS / Windows toolchain older than the
 * release that first shipped it (with a warning), instead of failing native-image.
 *
 * @param graalvmVersion the toolchain's `GRAALVM_VERSION` ([graalvmVersionOf]). An unreadable
 *   version is treated as too old off Linux, since the build would fail rather than warn.
 */
internal fun resolveNativeImageGc(
    requested: NativeImageGarbageCollector?,
    isOracleGraalvm: Boolean,
    isLinux: Boolean,
    graalvmVersion: String?,
    graalvmHome: String,
): NativeImageGcResolution {
    if (requested == null) return NativeImageGcResolution(gc = null, warning = null)

    val minimum = requested.nonLinuxMinVersion
    val unsupportedReason =
        when {
            requested.isOracleOnly && !isOracleGraalvm ->
                "${requested.flag} requires Oracle GraalVM (current toolchain: $graalvmHome)"
            minimum != null && !isLinux && graalvmVersion == null ->
                "${requested.flag} requires GraalVM $minimum or newer outside Linux, and the " +
                    "version of $graalvmHome could not be read"
            minimum != null && !isLinux && !isAtLeastVersion(graalvmVersion!!, minimum) ->
                "${requested.flag} requires GraalVM $minimum or newer outside Linux " +
                    "(current toolchain: $graalvmVersion)"
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
 * Compares two dotted GraalVM versions component by component, a missing component counting as 0
 * (`"25.4" >= "25.4"`, `"25.3.4.1" < "25.4"`). Non-numeric components compare as 0, so an
 * unexpected qualifier never promotes a toolchain past the minimum.
 */
private fun isAtLeastVersion(
    version: String,
    minimum: String,
): Boolean {
    val actual = version.split('.')
    val required = minimum.split('.')
    for (i in 0 until maxOf(actual.size, required.size)) {
        val a = actual.getOrNull(i)?.toIntOrNull() ?: 0
        val r = required.getOrNull(i)?.toIntOrNull() ?: 0
        if (a != r) return a > r
    }
    return true
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
