package dev.nucleusframework.window.jewel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.window.ControlButtonsDirection
import dev.nucleusframework.window.DecoratedDialogScope
import dev.nucleusframework.window.DecoratedDialogState
import dev.nucleusframework.window.DialogTitleBar
import dev.nucleusframework.window.TitleBarScope
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.styling.TitleBarStyle
import org.jetbrains.jewel.foundation.theme.LocalContentColor

@Suppress("FunctionNaming")
@Composable
public fun DecoratedDialogScope.JewelDialogTitleBar(
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
