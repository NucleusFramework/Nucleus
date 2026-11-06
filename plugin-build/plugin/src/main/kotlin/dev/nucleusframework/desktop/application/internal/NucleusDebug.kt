package dev.nucleusframework.desktop.application.internal

import org.gradle.api.logging.Logger

/** `-Pnucleus.debug=tint,stats,verify,recomposition`: debug switches for one run, on top of `debug { }`. */
internal const val NUCLEUS_DEBUG_GRADLE_PROPERTY = "nucleus.debug"

/** Runtime switches the `debug { }` DSL maps to; keep in sync with decorated-window-tao. */
internal const val PARTIAL_REDRAW_TINT_PROPERTY = "nucleus.tao.partialRedraw.tint"
internal const val PARTIAL_REDRAW_STATS_PROPERTY = "nucleus.tao.partialRedraw.debug"
internal const val PARTIAL_REDRAW_VERIFY_PROPERTY = "nucleus.tao.partialRedraw.verify"
internal const val RECOMPOSITION_PROPERTY = "nucleus.compose.recomposition"
internal const val RECOMPOSITION_TOP_PROPERTY = "nucleus.compose.recomposition.top"

private val PARTIAL_REDRAW_SWITCHES = setOf("tint", "stats", "verify")
private val DEBUG_SWITCHES = PARTIAL_REDRAW_SWITCHES + "recomposition"

/**
 * The `run` task's `-D` flags for [JvmApplicationData.debug], plus the switches
 * named in [cli] (the `nucleus.debug` Gradle property, comma-separated).
 * Warns about unknown switches, and about partial redraw switches while partial
 * redraw is off: Compose is then not patched and they would do nothing.
 */
internal fun nucleusDebugJvmArgs(
    app: JvmApplicationData,
    cli: String?,
    logger: Logger,
): List<String> {
    val requested =
        cli
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
            .toSet()
    (requested - DEBUG_SWITCHES).forEach {
        logger.warn(
            "Nucleus: unknown -P$NUCLEUS_DEBUG_GRADLE_PROPERTY switch '$it' (known: ${DEBUG_SWITCHES.joinToString()})",
        )
    }
    val partialRedraw = app.debug.partialRedraw
    val recomposition = app.debug.recomposition
    val tint = partialRedraw.tint || "tint" in requested
    val stats = partialRedraw.stats || "stats" in requested
    val verify = partialRedraw.verify || "verify" in requested
    if ((tint || stats || verify) && !app.optPartialRedraw) {
        logger.warn(
            "Nucleus: debug { partialRedraw { } } needs partial redraw, which is off " +
                "(nucleusOptimization { partialRedraw = true }); every frame repaints in full.",
        )
    }
    return buildList {
        if (tint) add("-D$PARTIAL_REDRAW_TINT_PROPERTY=true")
        if (stats) add("-D$PARTIAL_REDRAW_STATS_PROPERTY=true")
        if (verify) add("-D$PARTIAL_REDRAW_VERIFY_PROPERTY=true")
        if (recomposition.enabled || "recomposition" in requested) {
            add("-D$RECOMPOSITION_PROPERTY=true")
            add("-D$RECOMPOSITION_TOP_PROPERTY=${recomposition.top}")
        }
    }
}
