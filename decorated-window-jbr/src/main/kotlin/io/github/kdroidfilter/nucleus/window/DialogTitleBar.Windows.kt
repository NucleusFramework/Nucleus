package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.jetbrains.JBR
import io.github.kdroidfilter.nucleus.window.internal.isDark
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.WindowMouseEventEffect

@Suppress("FunctionNaming")
@Composable
internal fun DecoratedDialogScope.WindowsDialogTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    content: @Composable TitleBarScope.(DecoratedDialogState) -> Unit = {},
) {
    val titleBar = remember { JBR.getWindowDecorations().createCustomTitleBar() }

    WindowMouseEventEffect(titleBar)

    val controlDir = controlButtonsDirection.resolve()
    val isRtl = controlDir == LayoutDirection.Rtl
    val controlsSide = if (isRtl) WindowControlsSide.Start else WindowControlsSide.End

    CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
        DialogTitleBarImpl(
            modifier = modifier,
            gradientStartColor = gradientStartColor,
            style = style,
            controlButtonsDirection = controlDir,
            layoutPolicy = layoutPolicy,
            applyTitleBar = { height, _ ->
                titleBar.putProperty("controls.rtl", isRtl)
                titleBar.height = height.value
                titleBar.putProperty("controls.dark", style.colors.background.isDark())
                JBR.getWindowDecorations().setCustomTitleBar(window, titleBar)
                val padding =
                    if (isRtl) {
                        PaddingValues(start = titleBar.rightInset.dp, end = titleBar.leftInset.dp)
                    } else {
                        PaddingValues(start = titleBar.leftInset.dp, end = titleBar.rightInset.dp)
                    }
                padding
            },
            backgroundContent = { Spacer(modifier = Modifier.fillMaxSize()) },
            content = content,
        )
    }
}
