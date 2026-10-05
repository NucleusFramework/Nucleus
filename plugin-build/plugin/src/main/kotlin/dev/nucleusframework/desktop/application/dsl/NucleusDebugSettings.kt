package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/**
 * Debug overlays and logs for the `run` task — never baked into a package, a
 * `runDistributable` or a native image.
 *
 * ```
 * nucleus.application {
 *     debug {
 *         partialRedraw { tint = true; stats = true }
 *         recomposition { enabled = true }
 *     }
 * }
 * ```
 *
 * `-Pnucleus.debug=tint,stats,verify,recomposition` turns any of them on for one
 * run without touching the build.
 */
abstract class NucleusDebugSettings
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /** Partial redraw (`nucleusOptimization { partialRedraw }`) debugging. */
        val partialRedraw: PartialRedrawDebugSettings = objects.newInstance(PartialRedrawDebugSettings::class.java)

        /** Configures [partialRedraw]. */
        fun partialRedraw(fn: Action<PartialRedrawDebugSettings>) {
            fn.execute(partialRedraw)
        }

        /** Recomposition counters. */
        val recomposition: RecompositionDebugSettings = objects.newInstance(RecompositionDebugSettings::class.java)

        /** Configures [recomposition]. */
        fun recomposition(fn: Action<RecompositionDebugSettings>) {
            fn.execute(recomposition)
        }
    }

/**
 * Partial redraw debugging. Needs partial redraw on
 * (`nucleusOptimization { partialRedraw }`, or the `nucleusOptimization` master).
 */
abstract class PartialRedrawDebugSettings {
    /**
     * Flashes what each frame changed in translucent red, every other frame,
     * over frames repainted in full — Android's "show GPU view updates".
     */
    var tint: Boolean = false

    /**
     * Logs, every few seconds, how much of the window frames repaint and the
     * layers that damaged them, and why frames repaint in full when that changes.
     */
    var stats: Boolean = false

    /**
     * Re-renders every partial frame in full off screen and logs any pixel that
     * differs: the oracle for missed damage. Slow; turns [tint] off.
     */
    var verify: Boolean = false
}

/** Counts how often each composable runs (composition and recomposition). */
abstract class RecompositionDebugSettings {
    /** Logs the composables that ran the most, every few seconds. */
    var enabled: Boolean = false

    /** How many composables each report lists. */
    var top: Int = DEFAULT_TOP

    private companion object {
        const val DEFAULT_TOP = 10
    }
}
