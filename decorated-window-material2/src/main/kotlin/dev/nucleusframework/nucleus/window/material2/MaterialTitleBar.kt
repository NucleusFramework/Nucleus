package dev.nucleusframework.nucleus.window.material2

import androidx.compose.material.LocalContentColor
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

/**
 * Material 2 themed title bar.
 *
 * @param controlButtonsDirection Controls which side the window control buttons
 *   (close, minimize, maximize) are placed on, independently of the title bar
 *   content direction. Defaults to [ControlButtonsDirection.Auto].
 * @see ControlButtonsDirection
 */
@Suppress("FunctionNaming")
@Composable
fun DecoratedWindowScope.MaterialTitleBar(
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
