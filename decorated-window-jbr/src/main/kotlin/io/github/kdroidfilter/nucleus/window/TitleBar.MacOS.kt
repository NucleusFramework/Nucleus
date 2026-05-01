package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.jetbrains.JBR
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.WindowMouseEventEffect
import io.github.kdroidfilter.nucleus.window.utils.macos.MacUtil

@Suppress("FunctionNaming")
@Composable
internal fun AwtDecoratedWindowScope.MacOSTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    val newFullscreenControls = modifier.hasNewFullscreenControls()

    if (newFullscreenControls) {
        System.setProperty("apple.awt.newFullScreenControls", true.toString())
        System.setProperty(
            "apple.awt.newFullScreenControls.background",
            "${style.colors.fullscreenControlButtonsBackground.toArgb()}",
        )
        MacUtil.updateColors(window)
    } else {
        System.clearProperty("apple.awt.newFullScreenControls")
        System.clearProperty("apple.awt.newFullScreenControls.background")
    }

    val titleBar = remember { JBR.getWindowDecorations().createCustomTitleBar() }

    WindowMouseEventEffect(titleBar)

    val controlDir = controlButtonsDirection.resolve()
    val controlIsRtl = controlDir == LayoutDirection.Rtl

    TitleBarImpl(
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlDir,
        applyTitleBar = { height, titleBarState ->
            titleBar.putProperty("controls.rtl", controlIsRtl)
            titleBar.height = height.value
            JBR.getWindowDecorations().setCustomTitleBar(window, titleBar)

            if (titleBarState.isFullscreen && newFullscreenControls) {
                if (controlIsRtl) {
                    PaddingValues(end = 80.dp)
                } else {
                    PaddingValues(start = 80.dp)
                }
            } else {
                PaddingValues(start = titleBar.leftInset.dp, end = titleBar.rightInset.dp)
            }
        },
        onPlace = {
            if (state.isFullscreen) {
                MacUtil.updateFullScreenButtons(window)
            }
        },
        backgroundContent = backgroundContent,
        content = content,
    )
}
