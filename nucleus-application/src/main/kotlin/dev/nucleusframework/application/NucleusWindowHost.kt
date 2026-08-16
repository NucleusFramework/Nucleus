package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
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
 * Parameter surface matches [DecoratedWindow] (including Tao-only knobs such
 * as [popupFor], [nativePopupLayers], [nativeContextMenu], [hiddenFromDock]).
 */
public fun interface NucleusWindowHost {
    @Composable
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
        content: @Composable NucleusDecoratedWindowScope.() -> Unit,
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
    @Composable
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
        content: @Composable NucleusDecoratedDialogScope.() -> Unit,
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
        content: @Composable NucleusDecoratedWindowScope.() -> Unit,
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
        content: @Composable NucleusDecoratedDialogScope.() -> Unit,
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
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
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
    content: @Composable NucleusDecoratedDialogScope.() -> Unit,
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
