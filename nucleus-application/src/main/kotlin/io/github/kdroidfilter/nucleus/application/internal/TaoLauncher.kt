package io.github.kdroidfilter.nucleus.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.kdroidfilter.nucleus.application.LocalNucleusBackend
import io.github.kdroidfilter.nucleus.application.NucleusApplicationScope
import io.github.kdroidfilter.nucleus.application.NucleusBackend
import io.github.kdroidfilter.nucleus.application.TaoNucleusApplicationScope
import io.github.kdroidfilter.nucleus.window.tao.taoApplication

/**
 * Isolates references to Tao symbols. Loaded only when [NucleusBackend.Tao] is
 * chosen — keeps `nucleusApplication` callable on classpaths that lack the
 * `decorated-window-tao` module.
 */
internal object TaoLauncher {
    fun run(content: @Composable NucleusApplicationScope.() -> Unit) {
        taoApplication {
            val scope = TaoNucleusApplicationScope(this)
            CompositionLocalProvider(LocalNucleusBackend provides NucleusBackend.Tao) {
                scope.content()
            }
        }
    }
}
