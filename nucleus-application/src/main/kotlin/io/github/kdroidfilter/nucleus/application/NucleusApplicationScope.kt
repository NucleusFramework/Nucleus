package io.github.kdroidfilter.nucleus.application

import androidx.compose.runtime.Stable
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
}

internal class AwtNucleusApplicationScope(
    val composeScope: AwtApplicationScope,
) : NucleusApplicationScope {
    override val backend: NucleusBackend = NucleusBackend.Awt

    override fun exitApplication() = composeScope.exitApplication()
}

internal class TaoNucleusApplicationScope(
    val taoScope: TaoApplicationScope,
) : NucleusApplicationScope {
    override val backend: NucleusBackend = NucleusBackend.Tao

    override fun exitApplication() = taoScope.exitApplication()
}
