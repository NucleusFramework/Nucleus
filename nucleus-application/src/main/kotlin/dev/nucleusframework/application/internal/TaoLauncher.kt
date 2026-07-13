package dev.nucleusframework.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import dev.nucleusframework.application.LocalNucleusBackend
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.TaoNucleusApplicationScope
import dev.nucleusframework.window.tao.TaoDockPolicy
import dev.nucleusframework.window.tao.taoApplication

/**
 * Isolates references to Tao symbols. Loaded only when [NucleusBackend.Tao] is
 * chosen — keeps `nucleusApplication` callable on classpaths that lack the
 * `decorated-window-tao` module.
 */
internal object TaoLauncher {
    fun run(
        args: Array<String>,
        dockIconFollowsWindows: Boolean,
        content: @Composable NucleusApplicationScope.() -> Unit,
    ) {
        // macOS deep links arrive through Tao's `application:openURLs:` delegate
        // (forwarded by the native event loop to `TaoDeepLinkBridge`). The user's
        // callback is wired later from `TaoNucleusApplicationScope.onDeepLink { … }`;
        // URIs received before then are buffered and replayed by `TaoDeepLinkBridge`.
        taoApplication {
            val scope = TaoNucleusApplicationScope(this, args)
            CompositionLocalProvider(LocalNucleusBackend provides NucleusBackend.Tao) {
                // Apply the Dock-follows-windows opt-in on the Tao main thread
                // (this composition) so a tray-only app drops out of the Dock at
                // startup; visible DecoratedWindows then promote it as needed.
                LaunchedEffect(dockIconFollowsWindows) {
                    TaoDockPolicy.setEnabled(dockIconFollowsWindows)
                }
                scope.content()
            }
        }
    }
}
