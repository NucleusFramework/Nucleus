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
import androidx.compose.ui.window.v2.WindowState
import dev.nucleusframework.window.tao.DecoratedWindow as DecoratedWindowV1

/**
 * [DecoratedWindow] overload that accepts Compose Multiplatform 1.12's
 * experimental window API v2 ([androidx.compose.ui.window.v2.WindowState]).
 *
 * Requested geometry (`requestBounds`, `requestPlacement`, …) is applied
 * asynchronously; observed geometry (`bounds`, `placement`, `isMinimized`)
 * is published once the native window has been shown. [state] has no default
 * so `DecoratedWindow(onCloseRequest) { }` still resolves to the v1 overload.
 *
 * `requestScreen` / `screenId` are drained and ignored: Tao only exposes the
 * primary work area. Size/position providers that capture lambdas — including
 * the ones `requestSize` / `requestPosition` build internally — cannot be
 * evaluated without AWT and are logged and skipped; use
 * [requestInspectableBounds], [inspectableWindowBounds],
 * `WindowBoundsProvider.Absolute` or `requestBounds(DpRect)` instead.
 *
 * @param minSize Minimum inner size. [DpSize.Unspecified] means no minimum.
 * @param maxSize Maximum inner size. [DpSize.Unspecified] means no maximum.
 */
@Suppress("LongParameterList", "FunctionNaming")
@Composable
public fun ApplicationScope.DecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState,
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
    val v1 = rememberWindowStateV1(state)
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
            ApplyMaxSize(maxSize)
            CaptureNativeWindow(nativeWindow)
            content()
        },
    )
    BindWindowStateV2(state, v1, visible, nativeWindow.value)
}

/**
 * Publishes the scope's [TaoWindow] so the v2 bridge can read the real window
 * geometry when the v1 state never turns [androidx.compose.ui.window.WindowPosition.Absolute].
 */
@Composable
private fun TaoDecoratedWindowScope.CaptureNativeWindow(holder: MutableState<TaoWindow?>) {
    val window = this.window
    LaunchedEffect(window) { holder.value = window }
}

@Composable
private fun TaoDecoratedWindowScope.ApplyMaxSize(maxSize: DpSize) {
    val window = this.window
    LaunchedEffect(window, maxSize) {
        if (maxSize.width.isSpecified && maxSize.height.isSpecified) {
            window.setMaximumSize(
                maxSize.width.value.toDouble(),
                maxSize.height.value.toDouble(),
            )
        } else {
            window.setMaximumSize(null, null)
        }
    }
}
