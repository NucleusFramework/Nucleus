package io.github.kdroidfilter.nucleus.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalViewConfiguration
import com.jetbrains.JBR
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.linux.rememberLinuxButtonLayout
import java.awt.Frame
import java.awt.event.MouseEvent

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
    val linuxStyle = createLinuxTitleBarStyle(style)
    val controlDir = controlButtonsDirection.resolve()
    val controlsOnRight = rememberLinuxButtonLayout().controlsOnRight
    val controlsSide = if (controlsOnRight) WindowControlsSide.End else WindowControlsSide.Start

    var lastPress = 0L
    val viewConfig = LocalViewConfiguration.current
    CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
        TitleBarImpl(
            modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Main) {
                if (
                    this.currentEvent.button == PointerButton.Primary &&
                    this.currentEvent.changes.any { changed -> !changed.isConsumed }
                ) {
                    JBR.getWindowMove()?.startMovingTogetherWithMouse(window, MouseEvent.BUTTON1)
                    if (
                        System.currentTimeMillis() - lastPress in
                        viewConfig.doubleTapMinTimeMillis..viewConfig.doubleTapTimeoutMillis
                    ) {
                        if (state.isMaximized) {
                            window.extendedState = Frame.NORMAL
                        } else {
                            window.extendedState = Frame.MAXIMIZED_BOTH
                        }
                    }
                    lastPress = System.currentTimeMillis()
                }
            },
            gradientStartColor,
            linuxStyle,
            controlButtonsDirection = controlDir,
            layoutPolicy = layoutPolicy,
            applyTitleBar = { _, _ ->
                kdePaddingForButtonLayout()
            },
            backgroundContent = backgroundContent,
        ) { currentState ->
            WindowControlArea(window, currentState, linuxStyle)
            content(currentState)
        }
    }
}
