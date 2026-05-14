package io.github.kdroidfilter.nucleus.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle

/**
 * Platform-aware title bar for [DecoratedWindow].
 *
 * @param controlButtonsDirection Controls which side the window control buttons
 *   (close, minimize, maximize) are placed on, independently of the title bar
 *   content direction. Defaults to [ControlButtonsDirection.Auto] which follows
 *   the Compose [LocalLayoutDirection][androidx.compose.ui.platform.LocalLayoutDirection].
 */
@Suppress("FunctionNaming")
@Composable
fun DecoratedWindowScope.TitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    BasicTitleBar(
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlButtonsDirection,
        layoutPolicy = TitleBarLayoutPolicy.Default,
        backgroundContent = backgroundContent,
        content = content,
    )
}

@Suppress("FunctionNaming")
@Composable
fun DecoratedWindowScope.BasicTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    when (Platform.Current) {
        Platform.Linux ->
            LinuxTitleBar(
                modifier,
                gradientStartColor,
                style,
                controlButtonsDirection,
                layoutPolicy,
                backgroundContent,
                content,
            )
        Platform.Windows ->
            WindowsTitleBar(
                modifier,
                gradientStartColor,
                style,
                controlButtonsDirection,
                layoutPolicy,
                backgroundContent,
                content,
            )
        Platform.MacOS ->
            MacOSTitleBar(
                modifier,
                gradientStartColor,
                style,
                controlButtonsDirection,
                layoutPolicy,
                backgroundContent,
                content,
            )
        Platform.Unknown ->
            error("TitleBar is not supported on this platform(${System.getProperty("os.name")})")
    }
}
