package dev.nucleusframework.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.styling.TitleBarStyle

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
public fun DecoratedWindowScope.TitleBar(
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

@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun DecoratedWindowScope.BasicTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    // The jni backend always provides an [AwtDecoratedWindowScope] at runtime
    // (DecoratedWindow's content lambda is invoked with the AWT-bound subtype).
    // Cast here so app code can declare extensions on the abstract
    // `core.DecoratedWindowScope` and stay drop-in swappable with the tao backend.
    val awtScope = this as AwtDecoratedWindowScope
    when (Platform.Current) {
        Platform.Linux ->
            awtScope.LinuxTitleBar(
                modifier,
                gradientStartColor,
                style,
                controlButtonsDirection,
                layoutPolicy,
                backgroundContent,
                content,
            )
        Platform.Windows ->
            awtScope.WindowsTitleBar(
                modifier,
                gradientStartColor,
                style,
                controlButtonsDirection,
                layoutPolicy,
                backgroundContent,
                content,
            )
        Platform.MacOS ->
            awtScope.MacOSTitleBar(
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
