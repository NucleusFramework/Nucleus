package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle

@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun AwtDecoratedDialogScope.DialogTitleBarImpl(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: LayoutDirection = LocalLayoutDirection.current,
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
        applyTitleBar = applyTitleBar,
        onPlace = onPlace,
        backgroundContent = backgroundContent,
    ) { _ ->
        content(dialogState)
    }
}
