// #636: the window openers below are `@ComposableOpenTarget(-1)` with
// `@UiComposable` content lambdas — callable from any applier, always composing
// UI — so a non-UI composable called in the caller's scope cannot reclassify
// the window content. ktlint's `annotation` and `function-type-modifier-spacing`
// rules contradict each other on the resulting two-annotation parameter type.
@file:Suppress("ktlint:standard:annotation")

package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableOpenTarget
import androidx.compose.ui.UiComposable
import dev.nucleusframework.application.internal.TaoSatelliteWorkspaceAdapter
import dev.nucleusframework.window.tao.DefaultSatelliteHeader
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteScope
import dev.nucleusframework.window.tao.SatelliteWorkspace

/**
 * A satellite of a [SatelliteWorkspace]: declared once, hosted as a floating
 * window owned by the workspace's current owner or as a panel docked inside a
 * `DockLayout`, according to its placement.
 *
 * ```kotlin
 * nucleusApplication(args) {
 *     val workspace = rememberSatelliteWorkspace()
 *     DecoratedWindow(onCloseRequest = ::exitApplication) {
 *         JoinSatelliteWorkspace(workspace)
 *         WindowScaffold(titleBar = { TitleBar { Text("Document") } }) { padding ->
 *             DockLayout(workspace, Modifier.padding(padding)) { Document() }
 *         }
 *     }
 *     Satellite(workspace, id = "tools", title = "Tools") { ToolsPanel() }
 *     Satellite(
 *         workspace,
 *         id = "colors",
 *         title = "Colors",
 *         initialPlacement = SatellitePlacement.Docked(DockSide.Right),
 *     ) { ColorPanel() }
 * }
 * ```
 *
 * See [dev.nucleusframework.window.tao.Satellite] for the full contract:
 * `rememberSaveable` state survives dock / undock, the workspace remembers a
 * satellite after it leaves composition, and the owner follows focus between
 * the windows that joined. `rememberSatelliteWorkspace`, `JoinSatelliteWorkspace`
 * and `DockLayout` are used as-is from `decorated-window-tao`.
 *
 * @param nativeContextMenu whether text fields in the floating window get the
 *   native context menu, as for [SatelliteWindow].
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
@ComposableOpenTarget(-1)
public fun NucleusApplicationScope.Satellite(
    workspace: SatelliteWorkspace,
    id: String,
    title: String,
    initialPlacement: SatellitePlacement = SatellitePlacement.Floating(),
    initiallyOpen: Boolean = true,
    resizable: Boolean = true,
    hideWhileOwnerFullscreenOrMaximized: Boolean = true,
    nativeContextMenu: Boolean = true,
    header: @Composable @UiComposable SatelliteScope.() -> Unit = { DefaultSatelliteHeader() },
    content: @Composable @UiComposable SatelliteScope.() -> Unit,
) {
    when (this) {
        is TaoNucleusApplicationScope ->
            TaoSatelliteWorkspaceAdapter.Satellite(
                scope = this,
                workspace = workspace,
                id = id,
                title = title,
                initialPlacement = initialPlacement,
                initiallyOpen = initiallyOpen,
                resizable = resizable,
                hideWhileOwnerFullscreenOrMaximized = hideWhileOwnerFullscreenOrMaximized,
                nativeContextMenu = nativeContextMenu,
                header = header,
                content = content,
            )
    }
}

/**
 * Receiver-less [Satellite], resolving the application scope from
 * [LocalNucleusApplicationScope]. Fails outside a `nucleusApplication { … }` block.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
@ComposableOpenTarget(-1)
public fun Satellite(
    workspace: SatelliteWorkspace,
    id: String,
    title: String,
    initialPlacement: SatellitePlacement = SatellitePlacement.Floating(),
    initiallyOpen: Boolean = true,
    resizable: Boolean = true,
    hideWhileOwnerFullscreenOrMaximized: Boolean = true,
    nativeContextMenu: Boolean = true,
    header: @Composable @UiComposable SatelliteScope.() -> Unit = { DefaultSatelliteHeader() },
    content: @Composable @UiComposable SatelliteScope.() -> Unit,
) {
    LocalNucleusApplicationScope.current.Satellite(
        workspace = workspace,
        id = id,
        title = title,
        initialPlacement = initialPlacement,
        initiallyOpen = initiallyOpen,
        resizable = resizable,
        hideWhileOwnerFullscreenOrMaximized = hideWhileOwnerFullscreenOrMaximized,
        nativeContextMenu = nativeContextMenu,
        header = header,
        content = content,
    )
}

/**
 * [SatelliteWorkspace.pinTo] for the portable window handle: makes [window]
 * the owner of the workspace's floating satellites regardless of focus;
 * `null` returns to the focus-driven choice.
 */
public fun SatelliteWorkspace.pinTo(window: NucleusWindow?) {
    pinTo(window?.unsafe?.taoWindow)
}
