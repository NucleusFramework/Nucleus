package dev.nucleusframework.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.styling.TitleBarStyle

@Suppress("FunctionNaming")
@Composable
public fun DecoratedDialogScope.DialogTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    content: @Composable TitleBarScope.(DecoratedDialogState) -> Unit = {},
) {
    BasicDialogTitleBar(
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlButtonsDirection,
        layoutPolicy = TitleBarLayoutPolicy.Default,
        content = content,
    )
}

@Suppress("FunctionNaming")
@Composable
public fun DecoratedDialogScope.BasicDialogTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    content: @Composable TitleBarScope.(DecoratedDialogState) -> Unit = {},
) {
    val dialogTitleBarInfo = LocalDialogTitleBarInfo.current
    val titleBarInfo = remember { TitleBarInfo(dialogTitleBarInfo.title, dialogTitleBarInfo.icon) }
    LaunchedEffect(dialogTitleBarInfo.title) { titleBarInfo.title = dialogTitleBarInfo.title }
    LaunchedEffect(dialogTitleBarInfo.icon) { titleBarInfo.icon = dialogTitleBarInfo.icon }
    val awtScope = this as AwtDecoratedDialogScope
    CompositionLocalProvider(
        LocalTitleBarInfo provides titleBarInfo,
    ) {
        when (Platform.Current) {
            Platform.Linux ->
                awtScope.LinuxDialogTitleBar(
                    modifier,
                    gradientStartColor,
                    style,
                    controlButtonsDirection,
                    layoutPolicy,
                    content,
                )
            Platform.Windows ->
                awtScope.WindowsDialogTitleBar(
                    modifier,
                    gradientStartColor,
                    style,
                    controlButtonsDirection,
                    layoutPolicy,
                    content,
                )
            Platform.MacOS ->
                awtScope.MacOSDialogTitleBar(
                    modifier,
                    gradientStartColor,
                    style,
                    controlButtonsDirection,
                    layoutPolicy,
                    content,
                )
            Platform.Unknown ->
                error("DialogTitleBar is not supported on this platform(${System.getProperty("os.name")})")
        }
    }
}
