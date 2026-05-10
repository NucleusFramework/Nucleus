package dev.nucleusframework.nucleus.window.material2

import androidx.compose.material.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.nucleus.window.ControlButtonsDirection
import dev.nucleusframework.nucleus.window.DecoratedDialogScope
import dev.nucleusframework.nucleus.window.DecoratedDialogState
import dev.nucleusframework.nucleus.window.DialogTitleBar
import dev.nucleusframework.nucleus.window.TitleBarScope
import dev.nucleusframework.nucleus.window.styling.LocalTitleBarStyle
import dev.nucleusframework.nucleus.window.styling.TitleBarStyle

@Suppress("FunctionNaming")
@Composable
fun DecoratedDialogScope.MaterialDialogTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    content: @Composable TitleBarScope.(DecoratedDialogState) -> Unit = {},
) {
    DialogTitleBar(
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlButtonsDirection,
    ) { state ->
        CompositionLocalProvider(LocalContentColor provides style.colors.content) {
            content(state)
        }
    }
}
