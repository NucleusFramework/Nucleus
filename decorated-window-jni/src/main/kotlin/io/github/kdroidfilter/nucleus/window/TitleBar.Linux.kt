package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.linux.JniLinuxWindowBridge
import io.github.kdroidfilter.nucleus.window.utils.linux.rememberLinuxButtonLayout
import java.awt.Frame
import java.awt.MouseInfo

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
internal fun DecoratedWindowScope.LinuxTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    val controlDir = controlButtonsDirection.resolve()
    val controlsOnRight = rememberLinuxButtonLayout().controlsOnRight
    val controlsSide = if (controlsOnRight) WindowControlsSide.End else WindowControlsSide.Start

    if (JniLinuxWindowBridge.isLoaded) {
        NativeLinuxTitleBar(
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
        FallbackLinuxTitleBar(
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

// Native title bar: uses JNI to send _NET_WM_MOVERESIZE for native WM drag.
// Double-click to maximize is handled in Compose.
// Supports fullscreen sliding overlay via newFullscreenControls modifier.
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
private fun DecoratedWindowScope.NativeLinuxTitleBar(
    modifier: Modifier,
    gradientStartColor: Color,
    style: TitleBarStyle,
    controlButtonsDirection: LayoutDirection,
    layoutPolicy: TitleBarLayoutPolicy,
    controlsSide: WindowControlsSide,
    backgroundContent: @Composable () -> Unit,
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit,
) {
    val linuxStyle = createLinuxTitleBarStyle(style)
    val viewConfig = LocalViewConfiguration.current
    var lastPressTime = 0L

    val isNativeFullscreen = LocalNativeFullscreen.current
    val onExitFullscreen = LocalExitFullscreen.current
    val useNewFullscreenControls = modifier.hasNewFullscreenControls()

    // ── Fullscreen with newFullscreenControls: sliding overlay ──
    if (isNativeFullscreen && useNewFullscreenControls) {
        val holder = LocalFullscreenTitleBarHolder.current
        if (holder != null) {
            holder.compositionLocalContext = currentCompositionLocalContext
            holder.titleBarHeight = linuxStyle.metrics.height
            holder.content = {
                CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
                    TitleBarImpl(
                        modifier = modifier,
                        gradientStartColor = gradientStartColor,
                        style = linuxStyle,
                        controlButtonsDirection = controlButtonsDirection,
                        layoutPolicy = layoutPolicy,
                        applyTitleBar = { _, _ -> PaddingValues(0.dp) },
                    ) { currentState ->
                        WindowControlArea(
                            window = window,
                            state = currentState,
                            style = linuxStyle,
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
            style = linuxStyle,
            controlButtonsDirection = controlButtonsDirection,
            layoutPolicy = layoutPolicy,
            applyTitleBar = { _, _ ->
                kdePaddingForButtonLayout()
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
                                        // Double-click: toggle maximize
                                        if (state.isMaximized) {
                                            window.extendedState = Frame.NORMAL
                                        } else {
                                            window.extendedState = Frame.MAXIMIZED_BOTH
                                        }
                                    } else {
                                        // Single press: initiate native WM move
                                        val mouseLocation = MouseInfo.getPointerInfo()?.location
                                        if (mouseLocation != null) {
                                            JniLinuxWindowBridge.nativeStartWindowMove(
                                                window,
                                                mouseLocation.x,
                                                mouseLocation.y,
                                                1,
                                            )
                                        }
                                    }
                                    lastPressTime = now
                                }
                            },
                )
            },
        ) { currentState ->
            WindowControlArea(
                window = window,
                state = currentState,
                style = linuxStyle,
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
private fun DecoratedWindowScope.FallbackLinuxTitleBar(
    modifier: Modifier,
    gradientStartColor: Color,
    style: TitleBarStyle,
    controlButtonsDirection: LayoutDirection,
    layoutPolicy: TitleBarLayoutPolicy,
    controlsSide: WindowControlsSide,
    backgroundContent: @Composable () -> Unit,
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit,
) {
    val linuxStyle = createLinuxTitleBarStyle(style)
    val viewConfig = LocalViewConfiguration.current

    var lastPress = 0L

    CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
        TitleBarImpl(
            // Detect double-click to maximize/restore on the title bar area
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
            style = linuxStyle,
            controlButtonsDirection = controlButtonsDirection,
            layoutPolicy = layoutPolicy,
            applyTitleBar = { _, _ ->
                kdePaddingForButtonLayout()
            },
            backgroundContent = {
                backgroundContent()
                Spacer(modifier = Modifier.fillMaxSize().windowDragHandler(window))
            },
        ) { currentState ->
            WindowControlArea(window, currentState, linuxStyle)
            content(currentState)
        }
    }
}
