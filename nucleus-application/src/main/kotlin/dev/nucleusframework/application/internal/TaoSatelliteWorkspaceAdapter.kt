package dev.nucleusframework.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.nucleusframework.application.TaoNucleusApplicationScope
import dev.nucleusframework.application.internal.TaoSatelliteWindowAdapter.NucleusSatelliteScene
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteScope
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.Satellite as TaoSatellite

/**
 * Workspace satellites on Tao: the tao `Satellite` composable, with the
 * floating window's scene wrapped in the same Nucleus locals a standalone
 * satellite window gets ([TaoSatelliteWindowAdapter]). Docked content composes
 * inside the host window, where those locals already exist.
 */
internal object TaoSatelliteWorkspaceAdapter {
    @Suppress("LongParameterList")
    @Composable
    fun Satellite(
        scope: TaoNucleusApplicationScope,
        workspace: SatelliteWorkspace,
        id: String,
        title: String,
        initialPlacement: SatellitePlacement,
        initiallyOpen: Boolean,
        resizable: Boolean,
        hideWhileOwnerFullscreenOrMaximized: Boolean,
        nativeContextMenu: Boolean,
        header: @Composable SatelliteScope.() -> Unit,
        content: @Composable SatelliteScope.() -> Unit,
    ) {
        val outerLocals = currentCompositionLocalContext
        val parentLayoutDirection = LocalLayoutDirection.current
        with(scope.taoScope) {
            TaoSatellite(
                workspace = workspace,
                id = id,
                title = title,
                initialPlacement = initialPlacement,
                initiallyOpen = initiallyOpen,
                resizable = resizable,
                hideWhileOwnerFullscreenOrMaximized = hideWhileOwnerFullscreenOrMaximized,
                compositionLocalContext = outerLocals,
                floatingContentWrapper = { inner ->
                    NucleusSatelliteScene(outerLocals, parentLayoutDirection, nativeContextMenu) { inner() }
                },
                header = header,
                content = content,
            )
        }
    }
}
