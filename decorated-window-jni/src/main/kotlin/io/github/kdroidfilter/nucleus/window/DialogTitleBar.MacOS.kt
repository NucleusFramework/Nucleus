package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.macos.JniMacTitleBarBridge
import io.github.kdroidfilter.nucleus.window.utils.macos.JniMacWindowUtil

@Suppress("FunctionNaming")
@Composable
internal fun AwtDecoratedDialogScope.MacOSDialogTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    content: @Composable TitleBarScope.(DecoratedDialogState) -> Unit = {},
) {
    DisposableEffect(window) {
        onDispose {
            val ptr = JniMacWindowUtil.getWindowPtr(window)
            if (ptr != 0L) JniMacTitleBarBridge.nativeResetTitleBar(ptr)
        }
    }

    DialogTitleBarImpl(
        modifier = modifier.titleBarHitTestHandler(window),
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlButtonsDirection.resolve(),
        applyTitleBar = { height, _ ->
            JniMacWindowUtil.applyWindowProperties(window)

            val ptr = JniMacWindowUtil.getWindowPtr(window)
            val leftInset =
                if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
                    JniMacTitleBarBridge.nativeApplyTitleBar(ptr, height.value)
                } else {
                    @Suppress("MagicNumber")
                    val shrink = minOf(height.value / 28f, 1f)
                    @Suppress("MagicNumber")
                    height.value + 2f * shrink * 20f
                }
            PaddingValues(start = leftInset.dp)
        },
        backgroundContent = {
            Spacer(modifier = Modifier.fillMaxSize())
        },
        content = content,
    )
}
