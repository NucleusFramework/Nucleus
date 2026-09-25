// #636: `TabWindows` is a window opener — `@ComposableOpenTarget(-1)` with
// `@UiComposable` content lambdas. ktlint's `annotation` and
// `function-type-modifier-spacing` rules contradict each other on the
// resulting two-annotation parameter type.
@file:Suppress("ktlint:standard:annotation")

package dev.nucleusframework.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableOpenTarget
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.ui.UiComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.application.TaoNucleusApplicationScope
import dev.nucleusframework.window.tao.TabDragGhost
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
    @ComposableOpenTarget(-1)
    fun TabWindows(
        scope: TaoNucleusApplicationScope,
        workspace: TabWorkspace,
        strip: @Composable @UiComposable TabStripScope.() -> Unit,
        dragGhost: @Composable @UiComposable NucleusDecoratedWindowScope.(TabDragGhost) -> Unit,
        nativeContextMenu: Boolean,
        windowWrapper: @Composable @UiComposable NucleusDecoratedWindowScope.(content: @Composable () -> Unit) -> Unit,
        windowBodyWrapper: @Composable @UiComposable NucleusDecoratedWindowScope.(body: @Composable () -> Unit) -> Unit,
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
                // The ghost is a window of its own: it gets the Nucleus locals
                // a tab window gets, laid out in the direction of the strip the
                // tab came from — not the app's `windowWrapper`, which dresses
                // a window, background included.
                dragGhost = { ghost ->
                    bindNucleusContent(outerLocals, ghost.layoutDirection, nativeContextMenu) { dragGhost(ghost) }
                },
                windowContentWrapper = { inner ->
                    bindNucleusContent(outerLocals, parentLayoutDirection, nativeContextMenu) {
                        windowWrapper(inner)
                    }
                },
                // Inside the window's own scene, where `bindNucleusContent`
                // has already provided the Nucleus locals: the wrapper is
                // handed the same scope the content wrapper gets.
                windowBodyWrapper = { body ->
                    bindNucleusContent(outerLocals, parentLayoutDirection, nativeContextMenu) {
                        windowBodyWrapper(body)
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
