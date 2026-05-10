package dev.nucleusframework.nucleus.window.jewel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.nucleus.window.ControlButtonsDirection
import dev.nucleusframework.nucleus.window.DecoratedWindowScope
import dev.nucleusframework.nucleus.window.DecoratedWindowState
import dev.nucleusframework.nucleus.window.TitleBar
import dev.nucleusframework.nucleus.window.TitleBarScope
import dev.nucleusframework.nucleus.window.styling.LocalTitleBarStyle
import dev.nucleusframework.nucleus.window.styling.TitleBarStyle
import org.jetbrains.jewel.foundation.theme.LocalContentColor

@Suppress("FunctionNaming")
@Composable
fun DecoratedWindowScope.JewelTitleBar(
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
