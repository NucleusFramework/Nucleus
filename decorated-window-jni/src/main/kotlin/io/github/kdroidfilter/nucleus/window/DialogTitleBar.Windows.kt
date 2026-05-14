package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.windows.JniWindowsDecorationBridge
import io.github.kdroidfilter.nucleus.window.utils.windows.JniWindowsWindowUtil

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
    val controlDir = controlButtonsDirection.resolve()
    val controlsSide = if (controlDir == LayoutDirection.Rtl) WindowControlsSide.Start else WindowControlsSide.End

    if (JniWindowsDecorationBridge.isLoaded) {
        DisposableEffect(window) {
            val hwnd = JniWindowsWindowUtil.getHwnd(window)
            if (hwnd != 0L) JniWindowsDecorationBridge.nativeApplyDialogStyle(hwnd)
            onDispose {
                val h = JniWindowsWindowUtil.getHwnd(window)
                if (h != 0L) JniWindowsDecorationBridge.nativeUninstallDecoration(h)
            }
        }

        val titleBarBackground = style.colors.background
        LaunchedEffect(window, titleBarBackground) {
            val hwnd = JniWindowsWindowUtil.getHwnd(window)
            if (hwnd != 0L) {
                JniWindowsDecorationBridge.nativeSetBackgroundColor(hwnd, titleBarBackground.toArgb())
            }
        }
    }

    CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
        DialogTitleBarImpl(
            modifier = modifier,
            gradientStartColor = gradientStartColor,
            style = style,
            controlButtonsDirection = controlDir,
            layoutPolicy = layoutPolicy,
            applyTitleBar = { _, _ -> PaddingValues(0.dp) },
            backgroundContent = {
                Spacer(modifier = Modifier.fillMaxSize().windowDragHandler(window))
            },
        ) { dialogState ->
            WindowsDialogCloseButton(window, dialogState, style)
            content(dialogState)
        }
    }
}
