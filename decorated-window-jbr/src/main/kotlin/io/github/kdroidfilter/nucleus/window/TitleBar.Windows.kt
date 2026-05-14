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
internal fun DecoratedWindowScope.WindowsTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    val titleBar = remember { JBR.getWindowDecorations().createCustomTitleBar() }

    WindowMouseEventEffect(titleBar)

    val controlDir = controlButtonsDirection.resolve()
    val controlIsRtl = controlDir == LayoutDirection.Rtl
    val controlsSide = if (controlIsRtl) WindowControlsSide.Start else WindowControlsSide.End

    CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
        TitleBarImpl(
            modifier = modifier,
            gradientStartColor = gradientStartColor,
            style = style,
            controlButtonsDirection = controlDir,
            layoutPolicy = layoutPolicy,
            applyTitleBar = { height, _ ->
                titleBar.putProperty("controls.rtl", controlIsRtl)
                titleBar.height = height.value
                titleBar.putProperty("controls.dark", style.colors.background.isDark())
                JBR.getWindowDecorations().setCustomTitleBar(window, titleBar)
                PaddingValues(start = titleBar.leftInset.dp, end = titleBar.rightInset.dp)
            },
            backgroundContent = {
                Spacer(modifier = Modifier.fillMaxSize())
                backgroundContent()
            },
        ) { state ->
            content(state)
        }
    }
}
