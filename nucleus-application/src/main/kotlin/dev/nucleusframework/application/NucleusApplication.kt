package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.application
import dev.nucleusframework.application.internal.TaoLauncher
import dev.nucleusframework.core.runtime.WindowBackend
import dev.nucleusframework.graalvm.GraalVmInitializer
import java.util.Locale

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
    enableSingleInstance: Boolean = true,
    defaultLocale: Locale? = null,
    // macOS + Tao backend only: run as a menu-bar / agent app whose Dock icon
    // tracks window visibility. The app starts without a Dock icon (accessory
    // policy) and shows one only while at least one [DecoratedWindow] with
    // `hiddenFromDock = false` is visible; closing the last such window drops it
    // back out of the Dock. Standalone tray popups never count. Ignored on the
    // AWT backend and off macOS.
    dockIconFollowsWindows: Boolean = false,
    content: @Composable NucleusApplicationScope.() -> Unit,
) {
    GraalVmInitializer.initialize()

    // Apply the app-forced locale AFTER initialize(). On native-image macOS,
    // initialize() recovers the OS UI language into Locale.getDefault() (see
    // GraalVmInitializer.applyMacOsLocale); applying the app's choice here
    // guarantees it wins regardless of where the app sat in main(). Compose's
    // platform text menu (copy/cut/paste/select-all) reads Locale.getDefault()
    // lazily at first composition, so this must run before any UI is built.
    if (defaultLocale != null) {
        Locale.setDefault(defaultLocale)
        System.setProperty("user.language", defaultLocale.language)
        if (defaultLocale.country.isNotBlank()) {
            System.setProperty("user.country", defaultLocale.country)
        }
        if (defaultLocale.script.isNotBlank()) {
            System.setProperty("user.script", defaultLocale.script)
        }
    }

    if (enableSingleInstance) {
        acquireSingleInstanceLock(args)
    }

    primePlatformIntegrations(args)

    val resolved = resolveBackend(backend)

    // Record the resolved backend so external libraries (depending only on
    // core-runtime) can query WindowBackend.Current without a reflective
    // classpath probe or a Compose composition local.
    WindowBackend.setActive(
        if (resolved == NucleusBackend.Tao) WindowBackend.Tao else WindowBackend.Awt,
    )

    when (resolved) {
        NucleusBackend.Tao -> TaoLauncher.run(args, dockIconFollowsWindows, content)
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
            "dev.nucleusframework.window.tao.TaoApplication",
            false,
            NucleusBackend::class.java.classLoader,
        )
        true
    } catch (_: ClassNotFoundException) {
        false
    }
}
