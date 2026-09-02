@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.isSpecified
import dev.nucleusframework.window.tao.DecoratedDialog as DecoratedDialogV1
import dev.nucleusframework.window.tao.DecoratedWindow as DecoratedWindowV1
import dev.nucleusframework.window.tao.v2.DialogState as NucleusDialogState
import dev.nucleusframework.window.tao.v2.WindowState as NucleusWindowState

/**
 * [DecoratedWindow] overload for the AWT-free window API v2 clone
 * ([dev.nucleusframework.window.tao.v2.WindowState]).
 *
 * The whole v2 surface is applied — `requestBounds`, `requestSize`,
 * `requestPosition`, `requestScreen` — and `bounds` / `screenId` / `placement`
 * / `isMinimized` are published back from the native window. Compose's own
 * `androidx.compose.ui.window.v2.WindowState` is deliberately not accepted:
 * its geometry scope needs a displayable `java.awt.Window`, so half of it
 * would be inert here. See
 * [dev.nucleusframework.window.tao.v2.rememberWindowState] for the one-import
 * migration.
 *
 * @param minSize Minimum inner size. [DpSize.Unspecified] means no minimum.
 * @param maxSize Maximum inner size. [DpSize.Unspecified] means no maximum.
 */
@Suppress("LongParameterList", "FunctionNaming")
@Composable
public fun ApplicationScope.DecoratedWindow(
    onCloseRequest: () -> Unit,
    state: NucleusWindowState,
    title: String = "",
    icon: Painter? = null,
    minSize: DpSize = DpSize.Unspecified,
    maxSize: DpSize = DpSize.Unspecified,
    visible: Boolean = true,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    isDialog: Boolean = false,
    undecorated: Boolean = false,
    transparent: Boolean = false,
    popupFor: TaoWindow? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    nativePopupLayers: Boolean = false,
    macOSStyle: MacOSStyle = MacOSStyle.Classic,
    hiddenFromDock: Boolean = false,
    compositionLocalContext: CompositionLocalContext? = null,
    clickThrough: Boolean = false,
    visibleOnAllWorkspaces: Boolean = false,
    forceX11: Boolean = false,
    alwaysOnBottom: Boolean = false,
    content: @Composable TaoDecoratedWindowScope.() -> Unit,
) {
    val v1 = remember(state) { nucleusWindowStateToV1(state) }
    val nativeWindow = remember(state) { mutableStateOf<TaoWindow?>(null) }
    DecoratedWindowV1(
        onCloseRequest = onCloseRequest,
        state = v1,
        title = title,
        icon = icon,
        minimumSize = minSizeOrNull(minSize),
        visible = visible,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        alwaysOnTop = alwaysOnTop,
        isDialog = isDialog,
        undecorated = undecorated,
        transparent = transparent,
        popupFor = popupFor,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        nativePopupLayers = nativePopupLayers,
        macOSStyle = macOSStyle,
        hiddenFromDock = hiddenFromDock,
        compositionLocalContext = compositionLocalContext,
        clickThrough = clickThrough,
        visibleOnAllWorkspaces = visibleOnAllWorkspaces,
        forceX11 = forceX11,
        alwaysOnBottom = alwaysOnBottom,
        content = {
            ApplyMaxSizeNucleus(maxSize)
            CaptureNativeWindowNucleus(nativeWindow)
            content()
        },
    )
    BindNucleusWindowState(state, v1, visible, nativeWindow.value)
}

/**
 * [DecoratedDialog] overload for the AWT-free dialog API v2 clone
 * ([dev.nucleusframework.window.tao.v2.DialogState]).
 *
 * @param minSize Minimum inner size. [DpSize.Unspecified] means no minimum.
 * @param maxSize Maximum inner size. [DpSize.Unspecified] means no maximum.
 */
@Suppress("LongParameterList", "FunctionNaming")
@Composable
public fun ApplicationScope.DecoratedDialog(
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
    compositionLocalContext: CompositionLocalContext? = null,
    content: @Composable TaoDecoratedDialogScope.() -> Unit,
) {
    val v1 = remember(state) { nucleusDialogStateToV1(state) }
    val nativeWindow = remember(state) { mutableStateOf<TaoWindow?>(null) }
    // Same capture DecoratedDialog itself uses for the native owner relationship;
    // here it feeds `parentWindowMetrics` for AlignedToParentWindow.
    val parentWindow = LocalTaoWindow.current
    // Clamping is a side effect, not composition output: writing v1.size during
    // composition schedules a recomposition on every native resize past maxSize.
    LaunchedEffect(v1, v1.size, minSize, maxSize) {
        val clamped = clampSize(v1.size, minSize, maxSize)
        if (clamped != v1.size) {
            v1.size = clamped
        }
    }
    DecoratedDialogV1(
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
        compositionLocalContext = compositionLocalContext,
        content = {
            ApplySizeConstraintsNucleus(minSize, maxSize)
            CaptureNativeDialogWindowNucleus(nativeWindow)
            content()
        },
    )
    BindNucleusDialogState(state, v1, visible, minSize, maxSize, nativeWindow.value, parentWindow)
}

/** Publishes the scope's [TaoWindow] so the bridge can read real geometry. */
@Composable
private fun TaoDecoratedWindowScope.CaptureNativeWindowNucleus(holder: MutableState<TaoWindow?>) {
    val window = this.window
    LaunchedEffect(window) { holder.value = window }
}

@Composable
private fun TaoDecoratedDialogScope.CaptureNativeDialogWindowNucleus(holder: MutableState<TaoWindow?>) {
    val window = this.window
    LaunchedEffect(window) { holder.value = window }
}

@Composable
private fun TaoDecoratedWindowScope.ApplyMaxSizeNucleus(maxSize: DpSize) {
    val window = this.window
    LaunchedEffect(window, maxSize) {
        if (maxSize.width.isSpecified && maxSize.height.isSpecified) {
            window.setMaximumSize(maxSize.width.value.toDouble(), maxSize.height.value.toDouble())
        } else {
            window.setMaximumSize(null, null)
        }
    }
}

@Composable
private fun TaoDecoratedDialogScope.ApplySizeConstraintsNucleus(
    minSize: DpSize,
    maxSize: DpSize,
) {
    val window = this.window
    LaunchedEffect(window, minSize, maxSize) {
        val min = minSizeOrNull(minSize)
        if (min != null) {
            window.setMinimumSize(min.width.value.toDouble(), min.height.value.toDouble())
        } else {
            window.setMinimumSize(null, null)
        }
        if (maxSize.width.isSpecified && maxSize.height.isSpecified) {
            window.setMaximumSize(maxSize.width.value.toDouble(), maxSize.height.value.toDouble())
        } else {
            window.setMaximumSize(null, null)
        }
    }
}
