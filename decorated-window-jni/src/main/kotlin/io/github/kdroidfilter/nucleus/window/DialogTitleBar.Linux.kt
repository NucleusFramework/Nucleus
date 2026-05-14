package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.linux.JniLinuxWindowBridge
import java.awt.MouseInfo

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
internal fun DecoratedDialogScope.LinuxDialogTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    content: @Composable TitleBarScope.(DecoratedDialogState) -> Unit = {},
) {
    val controlDir = controlButtonsDirection.resolve()
    val controlsSide = if (controlDir == LayoutDirection.Rtl) WindowControlsSide.Start else WindowControlsSide.End

    if (JniLinuxWindowBridge.isLoaded) {
        NativeLinuxDialogTitleBar(
            modifier,
            gradientStartColor,
            style,
            controlDir,
            layoutPolicy,
            controlsSide,
            content,
        )
    } else {
        FallbackLinuxDialogTitleBar(
            modifier,
            gradientStartColor,
            style,
            controlDir,
            layoutPolicy,
            controlsSide,
            content,
        )
    }
}

// Native dialog title bar: uses JNI _NET_WM_MOVERESIZE for native WM drag.
// No double-click behavior for dialogs.
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
private fun DecoratedDialogScope.NativeLinuxDialogTitleBar(
    modifier: Modifier,
    gradientStartColor: Color,
    style: TitleBarStyle,
    controlButtonsDirection: LayoutDirection,
    layoutPolicy: TitleBarLayoutPolicy,
    controlsSide: WindowControlsSide,
    content: @Composable TitleBarScope.(DecoratedDialogState) -> Unit,
) {
    val linuxStyle = createLinuxTitleBarStyle(style)
    val dialogState = state

    CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
        DialogTitleBarImpl(
            modifier = modifier,
            gradientStartColor = gradientStartColor,
            style = linuxStyle,
            controlButtonsDirection = controlButtonsDirection,
            layoutPolicy = layoutPolicy,
            applyTitleBar = { _, _ ->
                val padding =
                    if (LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE) {
                        PaddingValues(end = 4.dp)
                    } else {
                        PaddingValues(0.dp)
                    }
                padding
            },
            backgroundContent = {
                Spacer(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onPointerEvent(PointerEventType.Press, PointerEventPass.Main) {
                                if (
                                    this.currentEvent.button == PointerButton.Primary &&
                                    this.currentEvent.changes.any { !it.isConsumed }
                                ) {
                                    // Initiate native WM move
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
                            },
                )
            },
        ) { _ ->
            DialogCloseButton(window, dialogState, linuxStyle)
            content(dialogState)
        }
    }
}

// Fallback dialog title bar: Compose-based drag (no native lib).
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
private fun DecoratedDialogScope.FallbackLinuxDialogTitleBar(
    modifier: Modifier,
    gradientStartColor: Color,
    style: TitleBarStyle,
    controlButtonsDirection: LayoutDirection,
    layoutPolicy: TitleBarLayoutPolicy,
    controlsSide: WindowControlsSide,
    content: @Composable TitleBarScope.(DecoratedDialogState) -> Unit,
) {
    val linuxStyle = createLinuxTitleBarStyle(style)
    val dialogState = state

    CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
        DialogTitleBarImpl(
            modifier =
                modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Main) {
                    // No double-click behavior for dialogs, drag is handled by the background Spacer.
                    if (
                        this.currentEvent.button == PointerButton.Primary &&
                        this.currentEvent.changes.any { !it.isConsumed }
                    ) {
                        // Intentional no-op.
                    }
                },
            gradientStartColor = gradientStartColor,
            style = linuxStyle,
            controlButtonsDirection = controlButtonsDirection,
            layoutPolicy = layoutPolicy,
            applyTitleBar = { _, _ ->
                val padding =
                    if (LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE) {
                        PaddingValues(end = 4.dp)
                    } else {
                        PaddingValues(0.dp)
                    }
                padding
            },
            backgroundContent = {
                Spacer(modifier = Modifier.fillMaxSize().windowDragHandler(window))
            },
        ) { _ ->
            DialogCloseButton(window, dialogState, linuxStyle)
            content(dialogState)
        }
    }
}
