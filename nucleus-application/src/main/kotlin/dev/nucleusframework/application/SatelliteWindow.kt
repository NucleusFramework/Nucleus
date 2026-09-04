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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import dev.nucleusframework.application.internal.TaoSatelliteWindowAdapter
import dev.nucleusframework.window.tao.SatelliteWindowState
import dev.nucleusframework.window.tao.rememberSatelliteWindowState

/**
 * Satellite window — an auxiliary window that belongs to another window.
 *
 * The floating tool palette / inspector / mixer archetype: anchored to its
 * parent by a `WindowPositioner`, moves with it, stays above it without being
 * modal, keeps out of the taskbar, hides while the parent is fullscreen or
 * maximized, and closes with it.
 *
 * ```kotlin
 * nucleusApplication(args) {
 *     DecoratedWindow(onCloseRequest = ::exitApplication) {
 *         TitleBar { Text("Document") }
 *         Button({ inspector = !inspector }) { Text("Inspector") }
 *         if (inspector) {
 *             SatelliteWindow(
 *                 onCloseRequest = { inspector = false },
 *                 state = rememberSatelliteWindowState(
 *                     size = DpSize(260.dp, 420.dp),
 *                     positioner = WindowPositioner(
 *                         parentAnchor = WindowAnchor.TopRight,
 *                         childAnchor = WindowAnchor.TopLeft,
 *                         offset = DpOffset(12.dp, 0.dp),
 *                     ),
 *                 ),
 *                 title = "Inspector",
 *             ) {
 *                 DialogTitleBar { Text("Inspector") }
 *                 InspectorPanel()
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * See [dev.nucleusframework.window.tao.SatelliteWindow] for the full contract
 * and the platform notes (native Wayland cannot position client windows, so
 * the anchoring degrades to compositor placement there).
 *
 * @param parent the owner window. Defaults to the enclosing window via
 *   [LocalNucleusWindow] — pass it explicitly to move a shared palette between
 *   document windows, which reparents it without changing its position.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
@ComposableOpenTarget(-1)
public fun NucleusApplicationScope.SatelliteWindow(
    onCloseRequest: () -> Unit,
    parent: NucleusWindow? = null,
    state: SatelliteWindowState = rememberSatelliteWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    focusable: Boolean = true,
    hideWhileParentFullscreenOrMaximized: Boolean = true,
    nativeContextMenu: Boolean = true,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable @UiComposable NucleusDecoratedWindowScope.() -> Unit,
) {
    when (this) {
        is TaoNucleusApplicationScope ->
            TaoSatelliteWindowAdapter.Satellite(
                scope = this,
                onCloseRequest = onCloseRequest,
                parent = parent,
                state = state,
                visible = visible,
                title = title,
                icon = icon,
                resizable = resizable,
                focusable = focusable,
                hideWhileParentFullscreenOrMaximized = hideWhileParentFullscreenOrMaximized,
                nativeContextMenu = nativeContextMenu,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
                content = content,
            )
    }
}

/**
 * Receiver-less [SatelliteWindow], resolving the application scope from
 * [LocalNucleusApplicationScope]. Parameters behave exactly like the
 * [NucleusApplicationScope] overload. Fails outside a `nucleusApplication { … }`
 * block, where no scope exists.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
@ComposableOpenTarget(-1)
public fun SatelliteWindow(
    onCloseRequest: () -> Unit,
    parent: NucleusWindow? = null,
    state: SatelliteWindowState = rememberSatelliteWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    focusable: Boolean = true,
    hideWhileParentFullscreenOrMaximized: Boolean = true,
    nativeContextMenu: Boolean = true,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable @UiComposable NucleusDecoratedWindowScope.() -> Unit,
) {
    LocalNucleusApplicationScope.current.SatelliteWindow(
        onCloseRequest = onCloseRequest,
        parent = parent,
        state = state,
        visible = visible,
        title = title,
        icon = icon,
        resizable = resizable,
        focusable = focusable,
        hideWhileParentFullscreenOrMaximized = hideWhileParentFullscreenOrMaximized,
        nativeContextMenu = nativeContextMenu,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        content = content,
    )
}
