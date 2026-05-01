package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.ControlButtonsDirection
import io.github.kdroidfilter.nucleus.window.DecoratedDialogState
import io.github.kdroidfilter.nucleus.window.GenericTitleBarImpl
import io.github.kdroidfilter.nucleus.window.LocalControlButtonsDirection
import io.github.kdroidfilter.nucleus.window.TitleBarScope
import io.github.kdroidfilter.nucleus.window.tao.TaoDecoratedDialogScope
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.linux.linuxTitleBarIcons
import io.github.kdroidfilter.nucleus.window.utils.windows.windowsTitleBarIcons

private val WINDOWS_DLG_BUTTON_WIDTH: Dp = 46.dp

// Fixed Windows-native close button colors — never theme-dependent.
@Suppress("MagicNumber")
private val WindowsCloseButtonHovered: Color = Color(0xFFE81123)

@Suppress("MagicNumber")
private val WindowsCloseButtonPressed: Color = Color(0xFFF1707A)

private val isKdeDlg: Boolean =
    Platform.Current == Platform.Linux &&
        LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE

/**
 * Tao-backed close-only title bar for [DecoratedDialog]. Mirrors
 * `decorated-window-jni`'s `DialogTitleBar`: same signature and the same
 * styling pipeline, with min/max stripped (dialogs render only the close
 * button on platforms that need a Compose-drawn chrome).
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun DecoratedDialogScope.DialogTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedDialogState) -> Unit = {},
) {
    val taoScope = this as TaoDecoratedDialogScope
    val taoWindow = taoScope.window
    val dialogState = taoScope.state
    val windowState = dialogState.toDecoratedWindowState()
    val controlDir = controlButtonsDirection.resolve()
    val viewConfig = LocalViewConfiguration.current

    GenericTitleBarImpl(
        state = windowState,
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlDir,
        applyTitleBar = { measuredHeight, _ ->
            when (Platform.Current) {
                Platform.MacOS -> {
                    val h = measuredHeight.value
                    val shrink = minOf(h / 28f, 1f)
                    val leftMargin = minOf(h / 2f, 20f)
                    val inset = (2f * leftMargin + 2f * shrink * 20f).dp
                    if (controlDir == androidx.compose.ui.unit.LayoutDirection.Rtl) {
                        PaddingValues(end = inset)
                    } else {
                        PaddingValues(start = inset)
                    }
                }
                else -> PaddingValues(0.dp)
            }
        },
        backgroundContent = backgroundContent,
        content = { _ ->
            content(dialogState)

            // Inject a Compose-drawn close button on platforms that don't
            // ship a native one alongside our custom title bar (Windows + Linux).
            // macOS keeps native traffic-lights painted by AppKit.
            when (Platform.Current) {
                Platform.Windows -> {
                    DialogWindowsCloseButton(
                        onClick = { taoWindow.requestUserClose() },
                        modifier = Modifier.align(Alignment.End),
                        style = style,
                    )
                }
                Platform.Linux -> {
                    DialogLinuxCloseButton(
                        onClick = { taoWindow.requestUserClose() },
                        state = windowState,
                        style = style,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
                else -> Unit
            }
            // Suppress unused-warning shim for viewConfig — kept for parity
            // with TitleBar's drag-handler chain (dialogs aren't draggable
            // by default on Tao; they sit on the parent's coordinate space).
            @Suppress("UNUSED_EXPRESSION")
            viewConfig
        },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
private fun TitleBarScope.DialogWindowsCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TitleBarStyle,
) {
    val icons = windowsTitleBarIcons()
    val interactionSource = remember { MutableInteractionSource() }
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalLayoutDirection provides LocalControlButtonsDirection.current,
    ) {
        Box(
            modifier = modifier
                .focusable(false)
                .size(WINDOWS_DLG_BUTTON_WIDTH, style.metrics.height)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) {
                    hovered = false
                    pressed = false
                }
                .onPointerEvent(PointerEventType.Press) { pressed = true }
                .onPointerEvent(PointerEventType.Release) { pressed = false },
            contentAlignment = Alignment.Center,
        ) {
            val bg = when {
                pressed -> WindowsCloseButtonPressed
                hovered -> WindowsCloseButtonHovered
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .size(WINDOWS_DLG_BUTTON_WIDTH, style.metrics.height)
                    .background(bg),
            )
            Image(
                painter = if (pressed || hovered) icons.closeHover else icons.close,
                contentDescription = "Close",
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
private fun TitleBarScope.DialogLinuxCloseButton(
    onClick: () -> Unit,
    state: io.github.kdroidfilter.nucleus.window.DecoratedWindowState,
    modifier: Modifier = Modifier,
    style: TitleBarStyle,
) {
    val icons = linuxTitleBarIcons()
    val interactionSource = remember { MutableInteractionSource() }
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }

    val closeHover = if (state.isActive) icons.closeHoverFocused else icons.closeHover
    val closePressed = if (state.isActive) icons.closePressedFocused else icons.closePressed

    androidx.compose.runtime.CompositionLocalProvider(
        LocalLayoutDirection provides LocalControlButtonsDirection.current,
    ) {
        Box(
            modifier = modifier
                .focusable(false)
                .let { if (isKdeDlg) it.size(style.metrics.titlePaneButtonSize) else it.size(style.metrics.titlePaneButtonSize) }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) {
                    hovered = false
                    pressed = false
                }
                .onPointerEvent(PointerEventType.Press) { pressed = true }
                .onPointerEvent(PointerEventType.Release) { pressed = false },
            contentAlignment = Alignment.Center,
        ) {
            val isCloseInteracted = hovered || pressed
            val currentIcon = when {
                pressed && (state.isActive || isKdeDlg) -> closePressed
                hovered && (state.isActive || isKdeDlg) -> closeHover
                else -> icons.close
            }
            val iconTint = style.colors.controlButtonIconColor
            val iconHoverTint = style.colors.controlButtonIconHoverColor
            val colorFilter = when {
                isCloseInteracted -> null
                (hovered || pressed) && iconHoverTint != Color.Unspecified -> ColorFilter.tint(iconHoverTint)
                iconTint != Color.Unspecified -> ColorFilter.tint(iconTint)
                else -> null
            }
            Image(
                painter = currentIcon,
                contentDescription = "Close",
                colorFilter = colorFilter,
            )
        }
    }
}
