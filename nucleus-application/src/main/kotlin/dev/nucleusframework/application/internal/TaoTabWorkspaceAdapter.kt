package dev.nucleusframework.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.application.TaoNucleusApplicationScope
import dev.nucleusframework.window.tao.TabScope
import dev.nucleusframework.window.tao.TabStripScope
import dev.nucleusframework.window.tao.TabWorkspace
import dev.nucleusframework.window.tao.Tab as TaoTab
import dev.nucleusframework.window.tao.TabWindows as TaoTabWindows

/**
 * Isolates references to Tao symbols for the tab archetype: the tao
 * `TabWindows` / `Tab` composables, with every window the workspace opens
 * wrapped in the same Nucleus locals a [dev.nucleusframework.application.DecoratedWindow]
 * gets ([bindNucleusContent]) — a tab window *is* a decorated window, the
 * workspace simply decides when it exists.
 */
internal object TaoTabWorkspaceAdapter {
    @Suppress("LongParameterList")
    @Composable
    fun TabWindows(
        scope: TaoNucleusApplicationScope,
        workspace: TabWorkspace,
        strip: @Composable TabStripScope.() -> Unit,
        nativeContextMenu: Boolean,
        windowWrapper: @Composable NucleusDecoratedWindowScope.(content: @Composable () -> Unit) -> Unit,
        onLastWindowClosed: () -> Unit,
    ) {
        // Each window the workspace opens gets a fresh ComposeScene — see
        // TaoDecoratedWindowAdapter for why the locals cross it as the scene's
        // own `compositionLocalContext` and not as a wrapping provider.
        val outerLocals = currentCompositionLocalContext
        val parentLayoutDirection = LocalLayoutDirection.current
        with(scope.taoScope) {
            TaoTabWindows(
                workspace = workspace,
                compositionLocalContext = outerLocals,
                strip = strip,
                windowContentWrapper = { inner ->
                    bindNucleusContent(outerLocals, parentLayoutDirection, nativeContextMenu) {
                        windowWrapper(inner)
                    }
                },
                onLastWindowClosed = onLastWindowClosed,
            )
        }
    }

    @Composable
    fun Tab(
        scope: TaoNucleusApplicationScope,
        workspace: TabWorkspace,
        id: String,
        title: String,
        group: String?,
        content: @Composable TabScope.() -> Unit,
    ) {
        with(scope.taoScope) {
            TaoTab(workspace = workspace, id = id, title = title, group = group, content = content)
        }
    }
}
