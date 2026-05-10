package io.github.kdroidfilter.nucleus.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.application
import io.github.kdroidfilter.nucleus.application.internal.TaoLauncher

/**
 * Single entry point for a Nucleus desktop application.
 *
 * Picks the window backend (AWT-based JBR/JNI or no-AWT Tao) and dispatches to
 * Compose Desktop's `application { … }` or Tao's `taoApplication { … }`.
 * Inside [content], use [DecoratedWindow] / [DecoratedDialog] — both work
 * uniformly across backends and expose a [NucleusWindow] handle.
 *
 * ```
 * fun main() = nucleusApplication(backend = NucleusBackend.Auto) {
 *     val state = rememberWindowState(size = DpSize(1200.dp, 800.dp))
 *     DecoratedWindow(
 *         onCloseRequest = ::exitApplication,
 *         state = state,
 *         title = "Demo",
 *     ) {
 *         TitleBar { Text(title) }
 *         // user content
 *     }
 * }
 * ```
 *
 * `Auto` resolution:
 *  1. Explicit [backend] (≠ [NucleusBackend.Auto]) is respected as-is.
 *  2. Otherwise the runtime classpath is probed. An app is expected to ship
 *     a single backend module — when both `decorated-window-tao` and an AWT
 *     backend (`-jbr` or `-jni`) are present, Tao wins.
 */
fun nucleusApplication(
    args: Array<String> = emptyArray(),
    backend: NucleusBackend = NucleusBackend.Auto,
    content: @Composable NucleusApplicationScope.() -> Unit,
) {
    when (resolveBackend(backend)) {
        NucleusBackend.Tao -> TaoLauncher.run(args, content)
        NucleusBackend.Awt, NucleusBackend.Auto ->
            application {
                val nucleusScope = AwtNucleusApplicationScope(this, args)
                CompositionLocalProvider(LocalNucleusBackend provides NucleusBackend.Awt) {
                    nucleusScope.content()
                }
            }
    }
}

internal fun resolveBackend(requested: NucleusBackend): NucleusBackend =
    when (requested) {
        NucleusBackend.Awt, NucleusBackend.Tao -> requested
        NucleusBackend.Auto ->
            when {
                TaoBackendOnClasspath -> NucleusBackend.Tao
                else -> NucleusBackend.Awt
            }
    }

/** Probes the classpath once. Tao ships `TaoApplication`; absence ⇒ AWT. */
private val TaoBackendOnClasspath: Boolean by lazy {
    try {
        Class.forName(
            "io.github.kdroidfilter.nucleus.window.tao.TaoApplication",
            false,
            NucleusBackend::class.java.classLoader,
        )
        true
    } catch (_: ClassNotFoundException) {
        false
    }
}
