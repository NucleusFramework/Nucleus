package dev.nucleusframework.application

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.nucleusframework.aot.runtime.AotRuntime
import dev.nucleusframework.aot.runtime.AotRuntimeMode
import dev.nucleusframework.core.runtime.DeepLinkHandler
import dev.nucleusframework.window.tao.TaoApplication
import dev.nucleusframework.window.tao.TaoDeepLinkBridge
import java.net.URI
import androidx.compose.ui.window.ApplicationScope as ComposeApplicationScope
import dev.nucleusframework.window.tao.ApplicationScope as TaoApplicationScope

/**
 * Scope exposed by [nucleusApplication], wrapping the Tao application scope so
 * [DecoratedWindow] never leaks backend types into user code.
 *
 * Extends Compose's [ComposeApplicationScope] so libraries scoped to the plain
 * Compose application scope (e.g. tray composables) work inside
 * [nucleusApplication] blocks without Nucleus-specific overloads.
 *
 * Composables that rely on AWT under the hood (Compose's `Tray`, `Window`, …)
 * are **not** supported: the process runs without an AWT event loop and the
 * native Tao event loop owns the main thread, so calling them compiles but AWT
 * would initialize off-thread (deadlock-prone on macOS). Use AWT-free
 * alternatives (e.g. ComposeNativeTray, [HostedWindow]).
 */
@Stable
public sealed interface NucleusApplicationScope : ComposeApplicationScope {
    /** Posts an exit request to the underlying event loop. */
    override fun exitApplication()

    /** Current AOT runtime mode, resolved from the `nucleus.aot.mode` system property. */
    public val aotMode: AotRuntimeMode get() = AotRuntime.mode()

    /** `true` when the JVM is running an AOT training pass. */
    public val isAotTraining: Boolean get() = aotMode == AotRuntimeMode.TRAINING

    /** `true` when the JVM is running with an AOT cache loaded. */
    public val isAotRuntime: Boolean get() = aotMode == AotRuntimeMode.RUNTIME

    /**
     * `true` while a system quit (macOS Cmd+Q, Dock → Quit, logout) is asking
     * the windows to close — see [TaoApplication.isQuitting]. A hide-to-tray
     * `onCloseRequest` checks it to let the quit through.
     */
    public val isQuitting: Boolean get() = TaoApplication.isQuitting

    /**
     * Registers [block] as the deep-link callback: the sink for the native
     * macOS Apple Events handler (installed pre-launch by `TaoLauncher`), plus
     * the CLI [args] passed to [nucleusApplication]. Any deep link delivered
     * before this call is buffered and replayed.
     */
    public fun onDeepLink(block: (URI) -> Unit)

    /**
     * Registers [block] for "the UI stopped responding" — Electron's
     * `unresponsive` event on a `webContents`, and the counterpart of
     * [onResponsive].
     *
     * Nucleus detects the stall by asking the OS (Windows `IsHungAppWindow`;
     * other platforms have no non-perturbing probe yet) and logs `SEVERE` with
     * a thread dump, but shows nothing: what the user sees is the app's
     * decision, exactly as in Electron. A crash-reporting hook, or the
     * browsers' "wait or quit" prompt, both belong here.
     *
     * ```kotlin
     * nucleusApplication(args) {
     *     onUnresponsive { crashReporter.reportHang() }
     *     onResponsive { crashReporter.hangEnded() }
     * }
     * ```
     *
     * **[block] runs on the watchdog thread, not the UI thread** — the UI
     * thread is the stuck one, so anything it posts there (Compose state,
     * `Dispatchers.Main`) would only run once the stall ends, if ever.
     */
    public fun onUnresponsive(block: () -> Unit): Unit = TaoApplication.onUnresponsive(block)

    /**
     * Registers [block] for "the UI is responding again" — Electron's
     * `responsive` event. Fired only after a stall that was reported through
     * [onUnresponsive]; same threading rules.
     */
    public fun onResponsive(block: () -> Unit): Unit = TaoApplication.onResponsive(block)

    /**
     * Runs [block] with the hang watchdog told that a stall is *expected* —
     * Chromium's `HangWatcher::InvalidateActiveExpectations()`.
     *
     * An operation the app knows is long and synchronous on the UI thread
     * looks exactly like a freeze from the outside, so wrap it and neither the
     * `SEVERE` report nor [onUnresponsive] fires for it. Everything else stays
     * watched, unlike `-Dnucleus.tao.watchdog=false`, which gives up on the
     * whole process.
     *
     * ```kotlin
     * expectUnresponsive { importHugeProjectSynchronously() }
     * ```
     *
     * Reentrant and thread-safe. Prefer moving the work off the UI thread;
     * this is for when that is not an option, not a way to silence a slow UI.
     */
    public fun <T> expectUnresponsive(block: () -> T): T = TaoApplication.expectUnresponsive(block)
}

/**
 * The [NucleusApplicationScope] of the surrounding [nucleusApplication].
 *
 * Secondary windows are rarely opened from the `nucleusApplication { … }`
 * lambda — they come from a navigation destination, a row action, an "edit in
 * a new window" button. This local makes the scope reachable from there
 * without threading the receiver through every layer in between:
 *
 * ```
 * @Composable
 * fun EditorWindow(onClose: () -> Unit) {
 *     with(LocalNucleusApplicationScope.current) {
 *         MaterialDecoratedWindow(onCloseRequest = onClose) { … }
 *     }
 * }
 * ```
 *
 * For plain [DecoratedWindow] / [DecoratedDialog] the receiver-less overloads
 * already read this local, so no `with` is needed.
 *
 * Libraries and navigation that must open a secondary window or dialog without
 * hard-coding Material/Jewel chrome should use [LocalNucleusWindowHost] /
 * [HostedWindow] and [LocalNucleusDialogHost] / [HostedDialog] instead of
 * Compose Desktop's AWT `Window` / `Dialog` (unsupported). Apps may override
 * either host to inject themed wrappers.
 *
 * Provided by [nucleusApplication]. Each window owns its own `ComposeScene`,
 * but the whole parent local context is bridged into it, so the scope (and the
 * window/dialog hosts) stay reachable from nested window content too.
 */
public val LocalNucleusApplicationScope: ProvidableCompositionLocal<NucleusApplicationScope> =
    staticCompositionLocalOf<NucleusApplicationScope> {
        error("LocalNucleusApplicationScope not provided — use it inside a nucleusApplication { … } block.")
    }

internal class TaoNucleusApplicationScope(
    val taoScope: TaoApplicationScope,
    private val args: Array<String>,
) : NucleusApplicationScope {
    override fun exitApplication() = taoScope.exitApplication()

    override fun onDeepLink(block: (URI) -> Unit) {
        DeepLinkHandler.setHandler(args, block)
        // Route native macOS Apple Events through DeepLinkHandler so the
        // `uri` cache used by SingleInstanceManager stays in sync.
        TaoDeepLinkBridge.setSink { DeepLinkHandler.deliver(it) }
    }
}
