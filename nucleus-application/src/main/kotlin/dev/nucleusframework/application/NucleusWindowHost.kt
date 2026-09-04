// #636: the window/dialog openers below are `@ComposableOpenTarget(-1)` with a
// `@UiComposable` content lambda — callable from any applier, always composing
// UI — so a non-UI composable called in the caller's scope cannot reclassify
// the window content. ktlint's `annotation` and `function-type-modifier-spacing`
// rules contradict each other on the resulting two-annotation parameter type.
@file:Suppress("ktlint:standard:annotation")

package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableOpenTarget
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.UiComposable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState

/**
 * Opens secondary windows on the active Nucleus backend.
 *
 * Compose Desktop's `androidx.compose.ui.window.Window` is hard-wired to AWT
 * and cannot run under the Tao event loop. Libraries and navigation layers
 * must open windows through this host (or [DecoratedWindow] /
 * [HostedWindow]) so Tao secondary scenes get a proper backend, scene-local
 * re-provisioning, and optional app chrome.
 *
 * Provided by [nucleusApplication] as [DefaultNucleusWindowHost]
 * ([DecoratedWindow]). Override when the app needs a themed wrapper
 * (Material, Jewel, custom chrome):
 *
 * ```
 * CompositionLocalProvider(
 *     LocalNucleusWindowHost provides myMaterialHost,
 * ) {
 *     // navigation / library code
 * }
 *
 * // library or WindowScene:
 * HostedWindow(
 *     onCloseRequest = onBack,
 *     state = rememberWindowState(size = DpSize(1200.dp, 800.dp)),
 *     title = "Deep search",
 * ) {
 *     TitleBar { Text(title) }
 *     DeepSearchContent()
 * }
 * ```
 *
 * Parameters follow [DecoratedWindow], Tao-only knobs included ([popupFor],
 * [nativePopupLayers], [nativeContextMenu], [hiddenFromDock],
 * [alwaysOnBottom]). Creation-time overlay flags that only make sense on a
 * top-level window ([DecoratedWindow]'s `transparent`, `clickThrough`,
 * `visibleOnAllWorkspaces`, `forceX11`) are not routed through the host.
 */
public fun interface NucleusWindowHost {
    /**
     * Opens a window hosting [content] on the active backend. Callable from
     * any applier — [content] is always composed as UI, in the new window's
     * own composition.
     */
    @Composable
    @ComposableOpenTarget(-1)
    public fun Window(
        onCloseRequest: () -> Unit,
        state: WindowState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        alwaysOnTop: Boolean,
        undecorated: Boolean,
        popupFor: NucleusWindow?,
        nativePopupLayers: Boolean,
        nativeContextMenu: Boolean,
        hiddenFromDock: Boolean,
        minimumSize: DpSize?,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        alwaysOnBottom: Boolean,
        content: @Composable @UiComposable NucleusDecoratedWindowScope.() -> Unit,
    )
}

/**
 * Opens secondary dialogs on the active Nucleus backend.
 *
 * Same motivation as [NucleusWindowHost]: avoid Compose Desktop's AWT
 * `Dialog` under Tao. Default is [DefaultNucleusDialogHost]
 * ([DecoratedDialog]).
 *
 * Parameter surface matches [DecoratedDialog].
 */
public fun interface NucleusDialogHost {
    /**
     * Opens a dialog hosting [content] on the active backend. Same applier
     * contract as [NucleusWindowHost.Window].
     */
    @Composable
    @ComposableOpenTarget(-1)
    public fun Dialog(
        onCloseRequest: () -> Unit,
        state: DialogState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        content: @Composable @UiComposable NucleusDecoratedDialogScope.() -> Unit,
    )
}

/**
 * [NucleusWindowHost] of the surrounding [nucleusApplication].
 *
 * Default: [DefaultNucleusWindowHost] ([DecoratedWindow]). Override to inject
 * Material / Jewel / app chrome without teaching every call site about the
 * concrete window type.
 */
public val LocalNucleusWindowHost: ProvidableCompositionLocal<NucleusWindowHost> =
    staticCompositionLocalOf {
        error(
            "LocalNucleusWindowHost not provided — use it inside a nucleusApplication { … } block, " +
                "or call DecoratedWindow { … } directly.",
        )
    }

/**
 * [NucleusDialogHost] of the surrounding [nucleusApplication].
 *
 * Default: [DefaultNucleusDialogHost] ([DecoratedDialog]).
 */
public val LocalNucleusDialogHost: ProvidableCompositionLocal<NucleusDialogHost> =
    staticCompositionLocalOf {
        error(
            "LocalNucleusDialogHost not provided — use it inside a nucleusApplication { … } block, " +
                "or call DecoratedDialog { … } directly.",
        )
    }

/**
 * Default [NucleusWindowHost]: opens a backend-agnostic [DecoratedWindow].
 * Does not draw a title bar — callers that need CSD chrome compose
 * [dev.nucleusframework.window.TitleBar] (or a Material/Jewel title bar)
 * inside [content], same as a top-level [DecoratedWindow].
 */
public object DefaultNucleusWindowHost : NucleusWindowHost {
    @Composable
    @ComposableOpenTarget(-1)
    override fun Window(
        onCloseRequest: () -> Unit,
        state: WindowState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        alwaysOnTop: Boolean,
        undecorated: Boolean,
        popupFor: NucleusWindow?,
        nativePopupLayers: Boolean,
        nativeContextMenu: Boolean,
        hiddenFromDock: Boolean,
        minimumSize: DpSize?,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        alwaysOnBottom: Boolean,
        content: @Composable @UiComposable NucleusDecoratedWindowScope.() -> Unit,
    ) {
        DecoratedWindow(
            onCloseRequest = onCloseRequest,
            state = state,
            visible = visible,
            title = title,
            icon = icon,
            resizable = resizable,
            enabled = enabled,
            focusable = focusable,
            alwaysOnTop = alwaysOnTop,
            undecorated = undecorated,
            popupFor = popupFor,
            nativePopupLayers = nativePopupLayers,
            nativeContextMenu = nativeContextMenu,
            hiddenFromDock = hiddenFromDock,
            minimumSize = minimumSize,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
            alwaysOnBottom = alwaysOnBottom,
            content = content,
        )
    }
}

/**
 * Default [NucleusDialogHost]: opens a backend-agnostic [DecoratedDialog].
 * Does not draw a title bar — same contract as [DefaultNucleusWindowHost].
 */
public object DefaultNucleusDialogHost : NucleusDialogHost {
    @Composable
    @ComposableOpenTarget(-1)
    override fun Dialog(
        onCloseRequest: () -> Unit,
        state: DialogState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        content: @Composable @UiComposable NucleusDecoratedDialogScope.() -> Unit,
    ) {
        DecoratedDialog(
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
}

/**
 * Opens a secondary window via [LocalNucleusWindowHost].
 *
 * Prefer this (or [DecoratedWindow]) over Compose Desktop's
 * `androidx.compose.ui.window.Window` under [nucleusApplication], especially
 * on the Tao backend.
 *
 * Defaults match [DecoratedWindow].
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
@ComposableOpenTarget(-1)
public fun HostedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    undecorated: Boolean = false,
    popupFor: NucleusWindow? = null,
    nativePopupLayers: Boolean = false,
    nativeContextMenu: Boolean = false,
    hiddenFromDock: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    alwaysOnBottom: Boolean = false,
    content: @Composable @UiComposable NucleusDecoratedWindowScope.() -> Unit,
) {
    LocalNucleusWindowHost.current.Window(
        onCloseRequest = onCloseRequest,
        state = state,
        visible = visible,
        title = title,
        icon = icon,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        alwaysOnTop = alwaysOnTop,
        undecorated = undecorated,
        popupFor = popupFor,
        nativePopupLayers = nativePopupLayers,
        nativeContextMenu = nativeContextMenu,
        hiddenFromDock = hiddenFromDock,
        minimumSize = minimumSize,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        alwaysOnBottom = alwaysOnBottom,
        content = content,
    )
}

/**
 * Opens a secondary dialog via [LocalNucleusDialogHost].
 *
 * Prefer this (or [DecoratedDialog]) over Compose Desktop's AWT `Dialog`
 * under [nucleusApplication], especially on the Tao backend.
 *
 * Defaults match [DecoratedDialog].
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
@ComposableOpenTarget(-1)
public fun HostedDialog(
    onCloseRequest: () -> Unit,
    state: DialogState = rememberDialogState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = false,
    enabled: Boolean = true,
    focusable: Boolean = true,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable @UiComposable NucleusDecoratedDialogScope.() -> Unit,
) {
    LocalNucleusDialogHost.current.Dialog(
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
