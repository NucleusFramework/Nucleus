package dev.nucleusframework.window.jewel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.window.ControlButtonsDirection
import dev.nucleusframework.window.DecoratedWindowScope
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.TitleBarScope
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.styling.TitleBarStyle
import org.jetbrains.jewel.foundation.theme.LocalContentColor

@Suppress("FunctionNaming")
@Composable
public fun DecoratedWindowScope.JewelTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    TitleBar(
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlButtonsDirection,
        backgroundContent = backgroundContent,
    ) { state ->
        CompositionLocalProvider(LocalContentColor provides style.colors.content) {
            content(state)
        }
    }
}
