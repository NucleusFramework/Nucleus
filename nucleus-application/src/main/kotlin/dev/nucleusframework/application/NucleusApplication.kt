package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.pollSystemTheme
import dev.nucleusframework.application.internal.TaoLauncher
import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.WindowBackend
import dev.nucleusframework.graalvm.GraalVmInitializer
import java.util.Locale

/**
 * Single entry point for a Nucleus desktop application.
 *
 * Runs the app on the no-AWT Tao backend (`decorated-window-tao`): a single
 * native event loop owns the main thread and doubles as `Dispatchers.Main`.
 * Inside [content], use [DecoratedWindow] / [DecoratedDialog], or
 * [HostedWindow] / [HostedDialog] when libraries must not hard-code chrome.
 * All open secondary windows/dialogs and expose a [NucleusWindow] handle.
 *
 * Compose's [androidx.compose.foundation.isSystemInDarkTheme] is bridged to
 * Nucleus's reactive OS detector (`darkmode-detector`), so official and library
 * call sites track live system theme changes without polling.
 *
 * ```
 * fun main() = nucleusApplication {
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
 * After the last window closes (or [NucleusApplicationScope.exitApplication]
 * is called), the JVM is terminated by default. Pass
 * `exitProcessOnExit = false` to return normally instead, matching Compose
 * Desktop's `application(exitProcessOnExit)`.
 */
@OptIn(ExperimentalComposeUiApi::class)
public fun nucleusApplication(
    args: Array<String> = emptyArray(),
    // Defaults to off in a dev run (`./gradlew run`, IDE launch) so a second
    // debug instance, or one started while a packaged copy is running, is not
    // silently forwarded to the first one and exited.
    enableSingleInstance: Boolean = !ExecutableRuntime.isDev(),
    defaultLocale: Locale? = null,
    // macOS only: run as a menu-bar / agent app whose Dock icon tracks window
    // visibility. The app starts without a Dock icon (accessory policy) and
    // shows one only while at least one [DecoratedWindow] with
    // `hiddenFromDock = false` is visible; closing the last such window drops it
    // back out of the Dock. Standalone tray popups never count. Ignored off
    // macOS.
    dockIconFollowsWindows: Boolean = false,
    // When true (default), the JVM is terminated after the application exits
    // (`exitProcess(0)` on a normal quit, `exitProcess(1)` after a fatal error).
    // When false, [nucleusApplication] returns so the caller can continue
    // in-process. The default matches Compose Desktop and is required because
    // Compose/Skiko initialisation indirectly touches AWT, whose non-daemon
    // EDT would otherwise keep the JVM alive after the Tao loop has shut down.
    exitProcessOnExit: Boolean = true,
    content: @Composable NucleusApplicationScope.() -> Unit,
) {
    GraalVmInitializer.initialize()

    // Apply the app-forced locale AFTER initialize(). On native-image macOS,
    // initialize() recovers the OS UI language into Locale.getDefault() (see
    // GraalVmInitializer.applyMacOsLocale); applying the app's choice here
    // guarantees it wins regardless of where the app sat in main(). Compose's
    // platform text menu (copy/cut/paste/select-all) reads Locale.getDefault()
    // lazily at first composition, so this must run before any UI is built.
    // SpellChecker.locale defaults to Locale.getDefault(), so this also
    // selects the spellcheck language unless the app overrides it.
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

    // Compose 1.12 polls the OS theme once a second on Dispatchers.IO for
    // isSystemInDarkTheme(). Nucleus provides LocalSystemTheme from its reactive
    // detector (ProvideNucleusSystemTheme), so that poll is pure overhead. The
    // flag is read when a scene is created, so it must be cleared before any UI.
    ComposeUiFlags.pollSystemTheme = false

    if (enableSingleInstance) {
        acquireSingleInstanceLock(args)
    }

    primePlatformIntegrations(args)

    // Record the active backend so external libraries (depending only on
    // core-runtime) can query WindowBackend.Current without a reflective
    // classpath probe or a Compose composition local.
    WindowBackend.setActive(WindowBackend.Tao)

    TaoLauncher.run(args, dockIconFollowsWindows, exitProcessOnExit, content)
}
