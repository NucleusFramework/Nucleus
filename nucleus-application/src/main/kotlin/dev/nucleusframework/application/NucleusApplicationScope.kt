package dev.nucleusframework.application

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.nucleusframework.aot.runtime.AotRuntime
import dev.nucleusframework.aot.runtime.AotRuntimeMode
import dev.nucleusframework.core.runtime.DeepLinkHandler
import dev.nucleusframework.window.tao.TaoDeepLinkBridge
import java.net.URI
import androidx.compose.ui.window.ApplicationScope as AwtApplicationScope
import dev.nucleusframework.window.tao.ApplicationScope as TaoApplicationScope

/**
 * Backend-agnostic scope exposed by [nucleusApplication]. The two concrete
 * subtypes wrap the AWT / Tao application scopes so [DecoratedWindow] can
 * dispatch on `when (this)` without leaking backend types into user code.
 *
 * Extends Compose's [AwtApplicationScope] so libraries scoped to the plain
 * Compose application scope (e.g. tray composables) work inside
 * [nucleusApplication] blocks without Nucleus-specific overloads.
 *
 * Composables that rely on AWT under the hood (Compose's `Tray`, `Window`, …)
 * are only supported on the AWT backends (JNI / JBR). On the Tao backend the
 * process runs without an AWT event loop and the native event loop owns the
 * main thread, so calling them compiles but is unsupported — AWT would
 * initialize off-thread (deadlock-prone on macOS). Use AWT-free alternatives
 * (e.g. ComposeNativeTray) with Tao.
 */
@Stable
sealed interface NucleusApplicationScope : AwtApplicationScope {
    /** Posts an exit request to the underlying event loop. */
    override fun exitApplication()

    /** The backend currently driving this scope. Never [NucleusBackend.Auto]. */
    val backend: NucleusBackend

    /** Current AOT runtime mode, resolved from the `nucleus.aot.mode` system property. */
    val aotMode: AotRuntimeMode get() = AotRuntime.mode()

    /** `true` when the JVM is running an AOT training pass. */
    val isAotTraining: Boolean get() = aotMode == AotRuntimeMode.TRAINING

    /** `true` when the JVM is running with an AOT cache loaded. */
    val isAotRuntime: Boolean get() = aotMode == AotRuntimeMode.RUNTIME

    /**
     * Registers [block] as the deep-link callback. Picks the right path for
     * the active backend:
     *  - AWT: installs the macOS Apple Events handler via `java.awt.Desktop`
     *    and parses the CLI [args] passed to [nucleusApplication].
     *  - Tao: registers the block as the sink for the native macOS Apple
     *    Events handler (installed pre-launch by `TaoLauncher`) and parses
     *    the CLI [args]. Any deep link delivered before this call is buffered
     *    and replayed.
     */
    fun onDeepLink(block: (URI) -> Unit)
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
 * Compose Desktop's AWT `Window` / `Dialog` (unsupported on Tao). Apps may
 * override either host to inject themed wrappers.
 *
 * Provided by [nucleusApplication] on both backends. On Tao each window owns
 * its own `ComposeScene`, but the whole parent local context is bridged into
 * it, so the scope (and the window/dialog hosts) stay reachable from nested
 * window content too.
 */
val LocalNucleusApplicationScope =
    staticCompositionLocalOf<NucleusApplicationScope> {
        error("LocalNucleusApplicationScope not provided — use it inside a nucleusApplication { … } block.")
    }

internal class AwtNucleusApplicationScope(
    val composeScope: AwtApplicationScope,
    private val args: Array<String>,
) : NucleusApplicationScope {
    override val backend: NucleusBackend = NucleusBackend.Awt

    override fun exitApplication() = composeScope.exitApplication()

    override fun onDeepLink(block: (URI) -> Unit) {
        DeepLinkHandler.installAwtAppleEventHandler()
        DeepLinkHandler.setHandler(args, block)
    }
}

internal class TaoNucleusApplicationScope(
    val taoScope: TaoApplicationScope,
    private val args: Array<String>,
) : NucleusApplicationScope {
    override val backend: NucleusBackend = NucleusBackend.Tao

    override fun exitApplication() = taoScope.exitApplication()

    override fun onDeepLink(block: (URI) -> Unit) {
        DeepLinkHandler.setHandler(args, block)
        // Route native macOS Apple Events through DeepLinkHandler so the
        // `uri` cache used by SingleInstanceManager stays in sync.
        TaoDeepLinkBridge.setSink { DeepLinkHandler.deliver(it) }
    }
}
