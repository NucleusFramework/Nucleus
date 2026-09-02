@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.window.tao.rememberSyncedNucleusDialogState
import dev.nucleusframework.window.tao.rememberSyncedNucleusWindowState
import dev.nucleusframework.window.tao.v2.DialogState as NucleusDialogState
import dev.nucleusframework.window.tao.v2.WindowState as NucleusWindowState

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
        alwaysOnBottom: Boolean,
        content: @Composable NucleusDecoratedWindowScope.() -> Unit,
    )

    /**
     * Opens a window driven by the AWT-free window API v2 clone
     * ([dev.nucleusframework.window.tao.v2.WindowState]).
     *
     * Default implementation converts [state] to v1 and calls [Window] so
     * existing themed hosts keep their chrome. `maxSize` is v2-only and is
     * dropped on that fallback, and geometry providers resolve against monitor
     * data only — the native window is not reachable from here. Override, or
     * use the `DecoratedWindow` overload directly, to get the full v2 path
     * (`requestScreen` included).
     */
    @Suppress("UnusedParameter")
    @Composable
    public fun Window(
        onCloseRequest: () -> Unit,
        state: NucleusWindowState,
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
        minSize: DpSize,
        maxSize: DpSize,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        alwaysOnBottom: Boolean,
        content: @Composable NucleusDecoratedWindowScope.() -> Unit,
    ) {
        val v1 = rememberSyncedNucleusWindowState(state, visible)
        Window(
            onCloseRequest = onCloseRequest,
            state = v1,
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
            minimumSize =
                if (minSize.width.isSpecified && minSize.height.isSpecified) minSize else null,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
            alwaysOnBottom = alwaysOnBottom,
            content = content,
        )
    }
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

    /**
     * Opens a dialog driven by the AWT-free dialog API v2 clone
     * ([dev.nucleusframework.window.tao.v2.DialogState]).
     *
     * Same fallback contract as the [NucleusWindowHost] clone overload:
     * `minSize` / `maxSize` are dropped and geometry providers see monitor
     * data only. Use the `DecoratedDialog` overload for the full v2 path.
     */
    @Suppress("UnusedParameter")
    @Composable
    public fun Dialog(
        onCloseRequest: () -> Unit,
        state: NucleusDialogState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        minSize: DpSize,
        maxSize: DpSize,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        content: @Composable NucleusDecoratedDialogScope.() -> Unit,
    ) {
        val v1 = rememberSyncedNucleusDialogState(state, visible)
        Dialog(
            onCloseRequest = onCloseRequest,
            state = v1,
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
        alwaysOnBottom: Boolean,
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
            alwaysOnBottom = alwaysOnBottom,
            content = content,
        )
    }

    /**
     * Full v2 path for the AWT-free clone: `DecoratedWindow` keeps `maxSize`
     * and hands the bridge the native window, so `requestScreen` and every
     * geometry provider are applied.
     */
    @Composable
    override fun Window(
        onCloseRequest: () -> Unit,
        state: NucleusWindowState,
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
        minSize: DpSize,
        maxSize: DpSize,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        alwaysOnBottom: Boolean,
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
            minSize = minSize,
            maxSize = maxSize,
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

    /** Full v2 path for the AWT-free clone. See [DefaultNucleusWindowHost]. */
    @Composable
    override fun Dialog(
        onCloseRequest: () -> Unit,
        state: NucleusDialogState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        minSize: DpSize,
        maxSize: DpSize,
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
            minSize = minSize,
            maxSize = maxSize,
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
    alwaysOnBottom: Boolean = false,
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

/**
 * Opens a secondary window via [LocalNucleusWindowHost] using the AWT-free
 * window API v2 clone ([dev.nucleusframework.window.tao.v2.WindowState]).
 *
 * `requestScreen` and every geometry provider are applied on the default host;
 * a themed host that does not override the clone overload falls back to the v1
 * surface (see [NucleusWindowHost.Window]).
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun HostedWindow(
    onCloseRequest: () -> Unit,
    state: NucleusWindowState,
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
    minSize: DpSize = DpSize.Unspecified,
    maxSize: DpSize = DpSize.Unspecified,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    alwaysOnBottom: Boolean = false,
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
        minSize = minSize,
        maxSize = maxSize,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        alwaysOnBottom = alwaysOnBottom,
        content = content,
    )
}

/**
 * Opens a secondary dialog via [LocalNucleusDialogHost] using the AWT-free
 * dialog API v2 clone ([dev.nucleusframework.window.tao.v2.DialogState]).
 *
 * Same host contract as the [HostedWindow] clone overload.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun HostedDialog(
    onCloseRequest: () -> Unit,
    state: NucleusDialogState,
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = false,
    enabled: Boolean = true,
    focusable: Boolean = true,
    minSize: DpSize = DpSize.Unspecified,
    maxSize: DpSize = DpSize.Unspecified,
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
        minSize = minSize,
        maxSize = maxSize,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        content = content,
    )
}
