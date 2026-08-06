package dev.nucleusframework.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.styling.TitleBarStyle

@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun AwtDecoratedDialogScope.DialogTitleBarImpl(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: LayoutDirection = LocalLayoutDirection.current,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    applyTitleBar: (Dp, DecoratedWindowState) -> PaddingValues,
    onPlace: (() -> Unit)? = null,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedDialogState) -> Unit,
) {
    val dialogState = state
    GenericTitleBarImpl(
        state = dialogState.toDecoratedWindowState(),
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlButtonsDirection,
        layoutPolicy = layoutPolicy,
        applyTitleBar = applyTitleBar,
        onPlace = onPlace,
        backgroundContent = backgroundContent,
    ) { _ ->
        content(dialogState)
    }
}
