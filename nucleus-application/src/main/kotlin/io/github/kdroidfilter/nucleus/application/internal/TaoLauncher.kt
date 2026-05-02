package io.github.kdroidfilter.nucleus.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.kdroidfilter.nucleus.application.LocalNucleusBackend
import io.github.kdroidfilter.nucleus.application.NucleusApplicationScope
import io.github.kdroidfilter.nucleus.application.NucleusBackend
import io.github.kdroidfilter.nucleus.application.TaoNucleusApplicationScope
import io.github.kdroidfilter.nucleus.window.tao.TaoDeepLinkBridge
import io.github.kdroidfilter.nucleus.window.tao.taoApplication

/**
 * Isolates references to Tao symbols. Loaded only when [NucleusBackend.Tao] is
 * chosen — keeps `nucleusApplication` callable on classpaths that lack the
 * `decorated-window-tao` module.
 */
internal object TaoLauncher {
    fun run(
        args: Array<String>,
        content: @Composable NucleusApplicationScope.() -> Unit,
    ) {
        // Install the macOS Apple Events handler before Tao starts NSApp's run
        // loop, so the cold-start kAEGetURL (app launched via a `nucleus://…`
        // link) is captured. The user's callback is wired later from
        // `TaoNucleusApplicationScope.onDeepLink { … }`; URIs received before
        // then are buffered and replayed by `TaoDeepLinkBridge`.
        TaoDeepLinkBridge.installNativeHandler()

        taoApplication {
            val scope = TaoNucleusApplicationScope(this, args)
            CompositionLocalProvider(LocalNucleusBackend provides NucleusBackend.Tao) {
                scope.content()
            }
        }
    }
}
