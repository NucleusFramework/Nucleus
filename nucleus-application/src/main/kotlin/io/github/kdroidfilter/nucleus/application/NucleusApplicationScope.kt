package io.github.kdroidfilter.nucleus.application

import androidx.compose.runtime.Stable
import io.github.kdroidfilter.nucleus.core.runtime.DeepLinkHandler
import io.github.kdroidfilter.nucleus.window.tao.TaoDeepLinkBridge
import java.net.URI
import androidx.compose.ui.window.ApplicationScope as AwtApplicationScope
import io.github.kdroidfilter.nucleus.window.tao.ApplicationScope as TaoApplicationScope

/**
 * Backend-agnostic scope exposed by [nucleusApplication]. The two concrete
 * subtypes wrap the AWT / Tao application scopes so [DecoratedWindow] can
 * dispatch on `when (this)` without leaking backend types into user code.
 */
@Stable
sealed interface NucleusApplicationScope {
    /** Posts an exit request to the underlying event loop. */
    fun exitApplication()

    /** The backend currently driving this scope. Never [NucleusBackend.Auto]. */
    val backend: NucleusBackend

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
