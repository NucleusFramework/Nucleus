package dev.nucleusframework.application

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.nucleusframework.aot.runtime.AotRuntime
import dev.nucleusframework.aot.runtime.AotRuntimeMode
import dev.nucleusframework.core.runtime.DeepLinkHandler
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
     * Registers [block] as the deep-link callback: the sink for the native
     * macOS Apple Events handler (installed pre-launch by `TaoLauncher`), plus
     * the CLI [args] passed to [nucleusApplication]. Any deep link delivered
     * before this call is buffered and replayed.
     */
    public fun onDeepLink(block: (URI) -> Unit)
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
