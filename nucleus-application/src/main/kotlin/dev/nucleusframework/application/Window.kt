// #636: the window/dialog openers below are `@ComposableOpenTarget(-1)` with a
// `@UiComposable` content lambda — callable from any applier, always composing
// UI — so a non-UI composable called in the caller's scope cannot reclassify
// the window content. ktlint's `annotation` and `function-type-modifier-spacing`
// rules contradict each other on the resulting two-annotation parameter type.
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("ktlint:standard:annotation")

package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableOpenTarget
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.UiComposable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.internal.TaoDecoratedDialogAdapter
import dev.nucleusframework.application.internal.TaoDecoratedWindowAdapter
import dev.nucleusframework.window.tao.v2.WindowState as NucleusWindowState

/**
 * A window with the platform's own title bar and frame (#841), on the Nucleus
 * backend. The counterpart of Compose Desktop's
 * `androidx.compose.ui.window.Window`, which is hard-wired to AWT: same
 * parameters and defaults, so moving a call site over is an import change.
 *
 * What draws the decorations:
 * - macOS: the standard AppKit title bar (title, traffic lights, toolbar
 *   behaviour, native drag and double-click), above the content.
 * - Windows: the Win32 caption and frame — native Snap Layouts, the Alt+Space
 *   system menu, DWM colours following the system.
 * - Linux: the window manager on X11, KWin's server-side decorations on Plasma
 *   (Wayland or X11), and GTK's own title bar where the compositor draws none
 *   (GNOME on Wayland).
 *
 * Nucleus draws no chrome of its own here: no window controls, resize band or
 * rounded frame. A [dev.nucleusframework.window.TitleBar] or
 * [dev.nucleusframework.window.WindowScaffold] in [content] stays plain app
 * content — it shows no window controls, reserves no room for them, and takes
 * no room at all when it holds nothing. Use [DecoratedWindow] for a
 * Compose-drawn title bar.
 *
 * [undecorated] keeps its Compose meaning: a fully borderless window, with no
 * decorations from either side.
 *
 * Inside [content], `nucleusWindow` is a portable [NucleusWindow] handle.
 *
 * The name is Compose's on purpose. A file importing both
 * `androidx.compose.ui.window.*` and `dev.nucleusframework.application.*` must
 * import this one explicitly (`import dev.nucleusframework.application.Window`),
 * or the call is ambiguous.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
@ComposableOpenTarget(-1)
public fun NucleusApplicationScope.Window(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "Untitled",
    icon: Painter? = null,
    undecorated: Boolean = false,
    transparent: Boolean = false,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable @UiComposable NucleusDecoratedWindowScope.() -> Unit,
) {
    when (this) {
        is TaoNucleusApplicationScope ->
            TaoDecoratedWindowAdapter.Window(
                scope = this,
                onCloseRequest = onCloseRequest,
                state = state,
                visible = visible,
                title = title,
                icon = icon,
                resizable = resizable,
                minimizable = true,
                maximizable = true,
                enabled = enabled,
                focusable = focusable,
                alwaysOnTop = alwaysOnTop,
                undecorated = undecorated,
                transparent = transparent,
                clickThrough = false,
                visibleOnAllWorkspaces = false,
                forceX11 = false,
                alwaysOnBottom = false,
                popupFor = null,
                nativePopupLayers = false,
                nativeContextMenu = false,
                hiddenFromDock = false,
                minimumSize = null,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
                systemDecorations = true,
                content = content,
            )
    }
}

/**
 * Receiver-less [Window], resolving the application scope from
 * [LocalNucleusApplicationScope] — callable from anywhere in the composition,
 * like Compose Desktop's `Window`. Fails outside a `nucleusApplication { … }`
 * block, where no scope exists.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
@ComposableOpenTarget(-1)
public fun Window(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "Untitled",
    icon: Painter? = null,
    undecorated: Boolean = false,
    transparent: Boolean = false,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable @UiComposable NucleusDecoratedWindowScope.() -> Unit,
) {
    LocalNucleusApplicationScope.current.Window(
        onCloseRequest = onCloseRequest,
        state = state,
        visible = visible,
        title = title,
        icon = icon,
        undecorated = undecorated,
        transparent = transparent,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        alwaysOnTop = alwaysOnTop,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        content = content,
    )
}

/**
 * [Window] overload for the AWT-free window API v2 clone
 * ([dev.nucleusframework.window.tao.v2.WindowState]). [state] has no default
 * so `Window(onCloseRequest) { }` still resolves to the v1 overload.
 */
@ExperimentalComposeUiApi
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun NucleusApplicationScope.Window(
    onCloseRequest: () -> Unit,
    state: NucleusWindowState,
    visible: Boolean = true,
    title: String = "Untitled",
    icon: Painter? = null,
    undecorated: Boolean = false,
    transparent: Boolean = false,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    minSize: DpSize = DpSize.Unspecified,
    maxSize: DpSize = DpSize.Unspecified,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    when (this) {
        is TaoNucleusApplicationScope ->
            TaoDecoratedWindowAdapter.WindowNucleusV2(
                scope = this,
                onCloseRequest = onCloseRequest,
                state = state,
                visible = visible,
                title = title,
                icon = icon,
                resizable = resizable,
                minimizable = true,
                maximizable = true,
                enabled = enabled,
                focusable = focusable,
                alwaysOnTop = alwaysOnTop,
                undecorated = undecorated,
                transparent = transparent,
                clickThrough = false,
                visibleOnAllWorkspaces = false,
                forceX11 = false,
                alwaysOnBottom = false,
                popupFor = null,
                nativePopupLayers = false,
                nativeContextMenu = false,
                hiddenFromDock = false,
                minSize = minSize,
                maxSize = maxSize,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
                systemDecorations = true,
                content = content,
            )
    }
}

/** Receiver-less v2 [Window], resolving the application scope from [LocalNucleusApplicationScope]. */
@ExperimentalComposeUiApi
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun Window(
    onCloseRequest: () -> Unit,
    state: NucleusWindowState,
    visible: Boolean = true,
    title: String = "Untitled",
    icon: Painter? = null,
    undecorated: Boolean = false,
    transparent: Boolean = false,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    minSize: DpSize = DpSize.Unspecified,
    maxSize: DpSize = DpSize.Unspecified,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    LocalNucleusApplicationScope.current.Window(
        onCloseRequest = onCloseRequest,
        state = state,
        visible = visible,
        title = title,
        icon = icon,
        undecorated = undecorated,
        transparent = transparent,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        alwaysOnTop = alwaysOnTop,
        minSize = minSize,
        maxSize = maxSize,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        content = content,
    )
}

/**
 * A dialog with the platform's own title bar and frame (#841): the [Window]
 * counterpart of Compose Desktop's `androidx.compose.ui.window.DialogWindow`,
 * owned by the window it is composed in, like [DecoratedDialog].
 *
 * Compose's `undecorated`, `transparent` and `alwaysOnTop` are not offered: the
 * Nucleus dialog path does not support them. A
 * [dev.nucleusframework.window.DialogTitleBar] in [content] shows no close
 * button and reserves no room for it.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
@ComposableOpenTarget(-1)
public fun NucleusApplicationScope.DialogWindow(
    onCloseRequest: () -> Unit,
    state: DialogState = rememberDialogState(),
    visible: Boolean = true,
    title: String = "Untitled",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable @UiComposable NucleusDecoratedDialogScope.() -> Unit,
) {
    when (this) {
        is TaoNucleusApplicationScope ->
            TaoDecoratedDialogAdapter.Dialog(
                scope = this,
                onCloseRequest = onCloseRequest,
                state = state,
                visible = visible,
                title = title,
                icon = icon,
                resizable = resizable,
                enabled = enabled,
                focusable = focusable,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
                systemDecorations = true,
                content = content,
            )
    }
}

/** Receiver-less [DialogWindow], resolving the application scope from [LocalNucleusApplicationScope]. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
@ComposableOpenTarget(-1)
public fun DialogWindow(
    onCloseRequest: () -> Unit,
    state: DialogState = rememberDialogState(),
    visible: Boolean = true,
    title: String = "Untitled",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable @UiComposable NucleusDecoratedDialogScope.() -> Unit,
) {
    LocalNucleusApplicationScope.current.DialogWindow(
        onCloseRequest = onCloseRequest,
        state = state,
        visible = visible,
        title = title,
        icon = icon,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        content = content,
    )
}
