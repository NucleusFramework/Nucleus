package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.windows.JniWindowsDecorationBridge
import io.github.kdroidfilter.nucleus.window.utils.windows.JniWindowsWindowUtil
import java.awt.Frame

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
internal fun DecoratedWindowScope.WindowsTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    val controlDir = controlButtonsDirection.resolve()
    val controlIsRtl = controlDir == LayoutDirection.Rtl
    val controlsSide = if (controlIsRtl) WindowControlsSide.Start else WindowControlsSide.End

    if (JniWindowsDecorationBridge.isLoaded) {
        NativeWindowsTitleBar(
            modifier,
            gradientStartColor,
            style,
            controlDir,
            layoutPolicy,
            controlsSide,
            backgroundContent,
            content,
        )
    } else {
        FallbackWindowsTitleBar(
            modifier,
            gradientStartColor,
            style,
            controlDir,
            layoutPolicy,
            controlsSide,
            backgroundContent,
            content,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun DecoratedWindowScope.NativeWindowsTitleBar(
    modifier: Modifier,
    gradientStartColor: Color,
    style: TitleBarStyle,
    controlButtonsDirection: LayoutDirection,
    layoutPolicy: TitleBarLayoutPolicy,
    controlsSide: WindowControlsSide,
    backgroundContent: @Composable () -> Unit,
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit,
) {
    val isNativeFullscreen = LocalNativeFullscreen.current
    val onExitFullscreen = LocalExitFullscreen.current
    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current
    var lastPressTime = 0L

    // Install decoration and clean up on dispose
    DisposableEffect(window) {
        val hwnd = JniWindowsWindowUtil.getHwnd(window)
        if (hwnd != 0L) {
            val heightPx = with(density) { style.metrics.height.roundToPx() }
            JniWindowsDecorationBridge.nativeInstallDecoration(hwnd, heightPx)

            onDispose {
                val h = JniWindowsWindowUtil.getHwnd(window)
                if (h != 0L) JniWindowsDecorationBridge.nativeUninstallDecoration(h)
            }
        } else {
            onDispose { }
        }
    }

    // Sync native background fill color with the title bar color so that
    // WM_ERASEBKGND fills with the correct color during resize (avoids white flash).
    val titleBarBackground = style.colors.background
    LaunchedEffect(window, titleBarBackground) {
        val hwnd = JniWindowsWindowUtil.getHwnd(window)
        if (hwnd != 0L) {
            JniWindowsDecorationBridge.nativeSetBackgroundColor(hwnd, titleBarBackground.toArgb())
        }
    }

    // Fix DPI scaling for min/max size on non-JBR JVMs (issue #102)
    SyncMinMaxSizeToNative(window)

    val useNewFullscreenControls = modifier.hasNewFullscreenControls()

    // ── Fullscreen with newFullscreenControls: sliding overlay ──
    if (isNativeFullscreen && useNewFullscreenControls) {
        LaunchedEffect(window) {
            val hwnd = JniWindowsWindowUtil.getHwnd(window)
            if (hwnd != 0L) JniWindowsDecorationBridge.nativeSetTitleBarHeight(hwnd, 0)
        }

        // Store rendering into the holder so DecoratedWindow can render it
        // as a floating overlay outside the DecoratedWindowBody layout.
        val holder = LocalFullscreenTitleBarHolder.current
        if (holder != null) {
            holder.compositionLocalContext = currentCompositionLocalContext
            holder.titleBarHeight = style.metrics.height
            holder.content = {
                CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
                    TitleBarImpl(
                        modifier = modifier,
                        gradientStartColor = gradientStartColor,
                        style = style,
                        controlButtonsDirection = controlButtonsDirection,
                        layoutPolicy = layoutPolicy,
                        applyTitleBar = { _, _ -> PaddingValues(0.dp) },
                    ) { currentState ->
                        WindowsWindowControlArea(
                            window = window,
                            state = currentState,
                            style = style,
                            isFullscreen = true,
                            onExitFullscreen = onExitFullscreen,
                        )
                        content(currentState)
                    }
                }
            }
        }
        return
    }

    // ── Normal title bar (or fullscreen without newFullscreenControls) ──
    CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
        TitleBarImpl(
            modifier = modifier,
            gradientStartColor = gradientStartColor,
            style = style,
            controlButtonsDirection = controlButtonsDirection,
            layoutPolicy = layoutPolicy,
            applyTitleBar = { height, currentState ->
                val hwnd = JniWindowsWindowUtil.getHwnd(window)
                if (hwnd != 0L) {
                    val heightPx = with(density) { height.roundToPx() }
                    JniWindowsDecorationBridge.nativeSetTitleBarHeight(hwnd, heightPx)
                }
                PaddingValues(0.dp)
            },
            backgroundContent = {
                backgroundContent()
                Spacer(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onPointerEvent(PointerEventType.Press, PointerEventPass.Main) {
                                if (
                                    this.currentEvent.button == PointerButton.Primary &&
                                    this.currentEvent.changes.any { !it.isConsumed }
                                ) {
                                    val now = System.currentTimeMillis()
                                    val elapsed = now - lastPressTime
                                    if (
                                        elapsed in
                                        viewConfig.doubleTapMinTimeMillis..viewConfig.doubleTapTimeoutMillis
                                    ) {
                                        if (state.isMaximized) {
                                            window.extendedState = Frame.NORMAL
                                        } else {
                                            window.extendedState = Frame.MAXIMIZED_BOTH
                                        }
                                    } else {
                                        val hwnd = JniWindowsWindowUtil.getHwnd(window)
                                        if (hwnd != 0L) {
                                            JniWindowsDecorationBridge.nativeStartDrag(hwnd)
                                        }
                                    }
                                    lastPressTime = now
                                }
                            },
                )
            },
        ) { currentState ->
            WindowsWindowControlArea(
                window = window,
                state = currentState,
                style = style,
                isFullscreen = isNativeFullscreen,
                onExitFullscreen = onExitFullscreen,
            )
            content(currentState)
        }
    }
}

// Fallback title bar: Compose-based drag and double-click (no native lib).
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
private fun DecoratedWindowScope.FallbackWindowsTitleBar(
    modifier: Modifier,
    gradientStartColor: Color,
    style: TitleBarStyle,
    controlButtonsDirection: LayoutDirection,
    layoutPolicy: TitleBarLayoutPolicy,
    controlsSide: WindowControlsSide,
    backgroundContent: @Composable () -> Unit,
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit,
) {
    val viewConfig = LocalViewConfiguration.current
    var lastPress = 0L

    CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
        TitleBarImpl(
            modifier =
                modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Main) {
                    if (
                        this.currentEvent.button == PointerButton.Primary &&
                        this.currentEvent.changes.any { !it.isConsumed }
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastPress in viewConfig.doubleTapMinTimeMillis..viewConfig.doubleTapTimeoutMillis) {
                            if (state.isMaximized) {
                                window.extendedState = Frame.NORMAL
                            } else {
                                window.extendedState = Frame.MAXIMIZED_BOTH
                            }
                        }
                        lastPress = now
                    }
                },
            gradientStartColor = gradientStartColor,
            style = style,
            controlButtonsDirection = controlButtonsDirection,
            layoutPolicy = layoutPolicy,
            applyTitleBar = { _, _ -> PaddingValues(0.dp) },
            backgroundContent = {
                backgroundContent()
                Spacer(modifier = Modifier.fillMaxSize().windowDragHandler(window))
            },
        ) { currentState ->
            WindowsWindowControlArea(window, currentState, style)
            content(currentState)
        }
    }
}

/**
 * Syncs [java.awt.Window.minimumSize] and [java.awt.Window.maximumSize] to the native
 * WM_GETMINMAXINFO handler so that DPI scaling is applied correctly on non-JBR JVMs.
 */
@Suppress("FunctionNaming")
@Composable
private fun SyncMinMaxSizeToNative(window: java.awt.Window) {
    DisposableEffect(window) {
        val hwnd = JniWindowsWindowUtil.getHwnd(window)
        if (hwnd != 0L) {
            val syncSizes = {
                val min = window.minimumSize
                JniWindowsDecorationBridge.nativeSetMinimumSize(hwnd, min.width, min.height)
                val max = window.maximumSize
                val maxW = if (max.width < Short.MAX_VALUE) max.width else 0
                val maxH = if (max.height < Short.MAX_VALUE) max.height else 0
                JniWindowsDecorationBridge.nativeSetMaximumSize(hwnd, maxW, maxH)
            }
            syncSizes()
            val propertyListener =
                java.beans.PropertyChangeListener { evt ->
                    if (evt.propertyName == "minimumSize" || evt.propertyName == "maximumSize") {
                        syncSizes()
                    }
                }
            window.addPropertyChangeListener(propertyListener)
            onDispose {
                window.removePropertyChangeListener(propertyListener)
                JniWindowsDecorationBridge.nativeSetMinimumSize(hwnd, 0, 0)
                JniWindowsDecorationBridge.nativeSetMaximumSize(hwnd, 0, 0)
            }
        } else {
            onDispose { }
        }
    }
}
