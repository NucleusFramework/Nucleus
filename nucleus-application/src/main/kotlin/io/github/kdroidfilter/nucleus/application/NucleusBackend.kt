package io.github.kdroidfilter.nucleus.application

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Selects the window backend used by [nucleusApplication].
 *
 * An application is expected to ship **exactly one** of the
 * `decorated-window-jbr` / `decorated-window-jni` / `decorated-window-tao`
 * runtime modules — their imports overlap by design. [Auto] detects which one
 * is on the classpath at runtime.
 */
enum class NucleusBackend {
    /** Detect at runtime: prefer Tao when present, else AWT (JBR/JNI). */
    Auto,

    /** AWT-bound backend (`decorated-window-jbr` or `decorated-window-jni`). */
    Awt,

    /** No-AWT backend (`decorated-window-tao`). */
    Tao,
}

/**
 * Composition local exposing the backend that the surrounding
 * [nucleusApplication] is running on. Internal libraries can branch on this
 * to adapt their behaviour without reflective classpath checks.
 *
 * Resolves to [NucleusBackend.Auto] outside of a [nucleusApplication] block.
 */
val LocalNucleusBackend = staticCompositionLocalOf { NucleusBackend.Auto }
