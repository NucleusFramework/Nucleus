package dev.nucleusframework.window.material

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.window.BasicTitleBar
import dev.nucleusframework.window.ControlButtonsDirection
import dev.nucleusframework.window.DecoratedWindowScope
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.TitleBarLayoutPolicy
import dev.nucleusframework.window.TitleBarScope
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.styling.TitleBarStyle

/**
 * Material 3 themed title bar.
 *
 * @param controlButtonsDirection Controls which side the window control buttons
 *   (close, minimize, maximize) are placed on, independently of the title bar
 *   content direction. Defaults to [ControlButtonsDirection.Auto].
 * @param layoutPolicy Layout policy applied to title bar children. Defaults to
 *   [TitleBarLayoutPolicy.Default]; use [TitleBarLayoutPolicy.FillCenter] to let
 *   the center child consume the remaining horizontal space between Start/End
 *   items.
 * @see ControlButtonsDirection
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun DecoratedWindowScope.MaterialTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    BasicTitleBar(
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlButtonsDirection,
        layoutPolicy = layoutPolicy,
        backgroundContent = backgroundContent,
    ) { state ->
        CompositionLocalProvider(LocalContentColor provides style.colors.content) {
            content(state)
        }
    }
}
