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
import dev.nucleusframework.application.internal.TaoTabWorkspaceAdapter
import dev.nucleusframework.window.tao.TabScope
import dev.nucleusframework.window.tao.TabStrip
import dev.nucleusframework.window.tao.TabStripScope
import dev.nucleusframework.window.tao.TabWorkspace

/**
 * One window per group of [workspace] — the Chrome tab model: a tab strip in
 * every window's title bar, the group's selected tab as its content, and
 * windows that follow the tabs instead of the app opening and closing them.
 *
 * ```kotlin
 * nucleusApplication(args) {
 *     val workspace = rememberTabWorkspace()
 *     TabWindows(workspace, onLastWindowClosed = ::exitApplication)
 *     for (document in documents) {
 *         Tab(workspace, id = document.id, title = document.name) { Editor(document) }
 *     }
 * }
 * ```
 *
 * See [dev.nucleusframework.window.tao.TabWindows] for the full contract:
 * a tear-off opens a window and the last tab out closes one, a tab dragged
 * onto another window's strip is inserted where it is dropped, and
 * `rememberSaveable` state inside a tab survives every move.
 * `rememberTabWorkspace`, `TabStrip` and `Modifier.tabDragHandle` are used
 * as-is from `decorated-window-tao`.
 *
 * @param strip the chrome of one window's tab strip; [TabStrip] by default.
 *   Composed inside that window's title bar.
 * @param nativeContextMenu whether text fields in the tab windows get the
 *   native context menu, as for [DecoratedWindow].
 * @param windowWrapper composed around each window's chrome and content, with
 *   that window's scope as receiver — where per-window chrome goes, since the
 *   app does not open these windows itself: `WindowBackground`,
 *   `WindowAppearance`, a themed `Surface`. Must invoke the lambda it is given.
 * @param onLastWindowClosed called every time the workspace goes from holding
 *   tabs to holding none, which is where an app calls `exitApplication`.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun NucleusApplicationScope.TabWindows(
    workspace: TabWorkspace,
    strip: @Composable TabStripScope.() -> Unit = { TabStrip() },
    nativeContextMenu: Boolean = true,
    windowWrapper: @Composable NucleusDecoratedWindowScope.(content: @Composable () -> Unit) -> Unit = { it() },
    onLastWindowClosed: () -> Unit = {},
) {
    when (this) {
        is TaoNucleusApplicationScope ->
            TaoTabWorkspaceAdapter.TabWindows(
                scope = this,
                workspace = workspace,
                strip = strip,
                nativeContextMenu = nativeContextMenu,
                windowWrapper = windowWrapper,
                onLastWindowClosed = onLastWindowClosed,
            )
    }
}

/**
 * Receiver-less [TabWindows], resolving the application scope from
 * [LocalNucleusApplicationScope]. Fails outside a `nucleusApplication { … }` block.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun TabWindows(
    workspace: TabWorkspace,
    strip: @Composable TabStripScope.() -> Unit = { TabStrip() },
    nativeContextMenu: Boolean = true,
    windowWrapper: @Composable NucleusDecoratedWindowScope.(content: @Composable () -> Unit) -> Unit = { it() },
    onLastWindowClosed: () -> Unit = {},
) {
    LocalNucleusApplicationScope.current.TabWindows(
        workspace = workspace,
        strip = strip,
        nativeContextMenu = nativeContextMenu,
        windowWrapper = windowWrapper,
        onLastWindowClosed = onLastWindowClosed,
    )
}

/**
 * Declares a tab of [workspace]. Which window shows it is the workspace's
 * business, so declare every tab once, next to [TabWindows], and never inside
 * one of its windows.
 *
 * On first declaration the tab joins [group] when given, else the window
 * focused last, else a new one; after that the workspace owns its placement
 * and an id already known only has its title and body refreshed.
 *
 * See [dev.nucleusframework.window.tao.Tab] for the full contract.
 *
 * @param id stable identity within the workspace.
 * @param title shown on the tab and, for the selected tab, as the window title.
 * @param group the group to open in on first declaration.
 * @param content the tab's body. `rememberSaveable` state in it survives a
 *   move between windows; plain `remember` state does not.
 */
@Suppress("FunctionNaming")
@Composable
@ComposableOpenTarget(-1)
public fun NucleusApplicationScope.Tab(
    workspace: TabWorkspace,
    id: String,
    title: String,
    group: String? = null,
    content: @Composable @UiComposable TabScope.() -> Unit,
) {
    when (this) {
        is TaoNucleusApplicationScope ->
            TaoTabWorkspaceAdapter.Tab(
                scope = this,
                workspace = workspace,
                id = id,
                title = title,
                group = group,
                content = content,
            )
    }
}

/**
 * Receiver-less [Tab], resolving the application scope from
 * [LocalNucleusApplicationScope]. Fails outside a `nucleusApplication { … }` block.
 */
@Suppress("FunctionNaming")
@Composable
@ComposableOpenTarget(-1)
public fun Tab(
    workspace: TabWorkspace,
    id: String,
    title: String,
    group: String? = null,
    content: @Composable @UiComposable TabScope.() -> Unit,
) {
    LocalNucleusApplicationScope.current.Tab(
        workspace = workspace,
        id = id,
        title = title,
        group = group,
        content = content,
    )
}
