package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.macos.JniMacTitleBarBridge
import io.github.kdroidfilter.nucleus.window.utils.macos.JniMacWindowUtil

@Suppress("FunctionNaming")
@Composable
internal fun DecoratedDialogScope.MacOSDialogTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    content: @Composable TitleBarScope.(DecoratedDialogState) -> Unit = {},
) {
    val controlDir = controlButtonsDirection.resolve()
    val controlIsRtl = controlDir == LayoutDirection.Rtl
    val controlsSide = if (controlIsRtl) WindowControlsSide.Start else WindowControlsSide.End

    DisposableEffect(window) {
        onDispose {
            val ptr = JniMacWindowUtil.getWindowPtr(window)
            if (ptr != 0L) JniMacTitleBarBridge.nativeResetTitleBar(ptr)
        }
    }

    CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
        DialogTitleBarImpl(
            modifier = modifier.titleBarHitTestHandler(window),
            gradientStartColor = gradientStartColor,
            style = style,
            controlButtonsDirection = controlDir,
            layoutPolicy = layoutPolicy,
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
                val padding = PaddingValues(start = leftInset.dp)
                padding
            },
            backgroundContent = {
                Spacer(modifier = Modifier.fillMaxSize())
            },
            content = content,
        )
    }
}
