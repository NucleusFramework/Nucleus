package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.layout.PaddingValues
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
import com.jetbrains.JBR
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import java.awt.event.MouseEvent

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
    val linuxStyle = createLinuxTitleBarStyle(style)
    val dialogState = state
    val controlDir = controlButtonsDirection.resolve()
    val controlsSide = if (controlDir == LayoutDirection.Rtl) WindowControlsSide.Start else WindowControlsSide.End

    CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
        DialogTitleBarImpl(
            modifier =
                modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Main) {
                    if (
                        this.currentEvent.button == PointerButton.Primary &&
                        this.currentEvent.changes.any { changed -> !changed.isConsumed }
                    ) {
                        JBR.getWindowMove()?.startMovingTogetherWithMouse(window, MouseEvent.BUTTON1)
                    }
                },
            gradientStartColor = gradientStartColor,
            style = linuxStyle,
            controlButtonsDirection = controlDir,
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
        ) { _ ->
            DialogCloseButton(window, dialogState, linuxStyle)
            content(dialogState)
        }
    }
}
