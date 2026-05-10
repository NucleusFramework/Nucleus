package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.linux.LinuxTitleBarButton
import io.github.kdroidfilter.nucleus.window.utils.linux.linuxTitleBarIcons
import io.github.kdroidfilter.nucleus.window.utils.linux.rememberLinuxButtonLayout
import java.awt.Frame
import java.awt.event.WindowEvent

private val isKde = LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE

@Suppress("FunctionNaming")
@Composable
fun TitleBarScope.WindowControlArea(
    window: java.awt.Window,
    state: DecoratedWindowState,
    style: TitleBarStyle,
    isFullscreen: Boolean = false,
    onExitFullscreen: (() -> Unit)? = null,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LocalControlButtonsDirection.current) {
        val icons = linuxTitleBarIcons()
        val layout = rememberLinuxButtonLayout()
        val buttonAlignment = if (layout.controlsOnRight) Alignment.End else Alignment.Start

        for (button in layout.buttons) {
            when (button) {
                LinuxTitleBarButton.CLOSE -> {
                    val closeHover = if (state.isActive) icons.closeHoverFocused else icons.closeHover
                    val closePressed = if (state.isActive) icons.closePressedFocused else icons.closePressed
                    ControlButton(
                        onClick = { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) },
                        state = state,
                        icon = icons.close,
                        iconHover = closeHover,
                        iconPressed = closePressed,
                        contentDescription = "Close",
                        style = style,
                        alignment = buttonAlignment,
                        isCloseButton = true,
                    )
                }

                LinuxTitleBarButton.MAXIMIZE -> {
                    if (isFullscreen && onExitFullscreen != null) {
                        ControlButton(
                            onClick = onExitFullscreen,
                            state = state,
                            icon = icons.maximize,
                            iconHover = icons.maximizeHover,
                            iconPressed = icons.maximizePressed,
                            contentDescription = "Exit fullscreen",
                            style = style,
                            alignment = buttonAlignment,
                        )
                    } else {
                        val frame = window as? Frame
                        if (frame != null && frame.isResizable) {
                            if (state.isMaximized) {
                                ControlButton(
                                    onClick = { frame.extendedState = Frame.NORMAL },
                                    state = state,
                                    icon = icons.restore,
                                    iconHover = icons.restoreHover,
                                    iconPressed = icons.restorePressed,
                                    contentDescription = "Restore",
                                    style = style,
                                    alignment = buttonAlignment,
                                )
                            } else {
                                ControlButton(
                                    onClick = { frame.extendedState = Frame.MAXIMIZED_BOTH },
                                    state = state,
                                    icon = icons.maximize,
                                    iconHover = icons.maximizeHover,
                                    iconPressed = icons.maximizePressed,
                                    contentDescription = "Maximize",
                                    style = style,
                                    alignment = buttonAlignment,
                                )
                            }
                        }
                    }
                }

                LinuxTitleBarButton.MINIMIZE -> {
                    ControlButton(
                        onClick = {
                            (window as? Frame)?.let {
                                it.extendedState = it.extendedState or Frame.ICONIFIED
                            }
                        },
                        state = state,
                        icon = icons.minimize,
                        iconHover = icons.minimizeHover,
                        iconPressed = icons.minimizePressed,
                        contentDescription = "Minimize",
                        style = style,
                        alignment = buttonAlignment,
                    )
                }
            }
        }
    }
}

/**
 * Close button for dialog title bars.
 * Unlike [WindowControlArea], this only shows the close button (no minimize/maximize).
 */
@Suppress("FunctionNaming")
@Composable
fun TitleBarScope.DialogCloseButton(
    window: java.awt.Window,
    state: DecoratedDialogState,
    style: TitleBarStyle,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LocalControlButtonsDirection.current) {
        val icons = linuxTitleBarIcons()
        val windowState = state.toDecoratedWindowState()
        val closeHover = if (windowState.isActive) icons.closeHoverFocused else icons.closeHover
        val closePressed = if (windowState.isActive) icons.closePressedFocused else icons.closePressed

        ControlButton(
            onClick = { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) },
            state = windowState,
            icon = icons.close,
            iconHover = closeHover,
            iconPressed = closePressed,
            contentDescription = "Close",
            style = style,
            isCloseButton = true,
        )
    }
}

@Suppress("FunctionNaming", "LongParameterList")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TitleBarScope.ControlButton(
    onClick: () -> Unit,
    state: DecoratedWindowState,
    icon: Painter,
    iconHover: Painter,
    iconPressed: Painter,
    contentDescription: String,
    style: TitleBarStyle,
    alignment: Alignment.Horizontal = Alignment.End,
    isCloseButton: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier =
            Modifier
                .align(alignment)
                .focusable(false)
                .let { if (isKde) it.offset(y = (-2).dp) else it }
                .size(style.metrics.titlePaneButtonSize)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        var hovered by remember { mutableStateOf(false) }
        var pressed by remember { mutableStateOf(false) }

        val isCloseInteracted = isCloseButton && (hovered || pressed)
        val currentIcon =
            when {
                pressed && (state.isActive || isKde) -> iconPressed
                hovered && (state.isActive || isKde) -> iconHover
                else -> icon
            }

        // Apply icon tint when controlButtonIconColor is set,
        // but skip tinting for close button hover/pressed (icons have baked-in colors).
        val iconTint = style.colors.controlButtonIconColor
        val iconHoverTint = style.colors.controlButtonIconHoverColor
        val colorFilter =
            when {
                isCloseInteracted -> null
                (hovered || pressed) && iconHoverTint != Color.Unspecified -> ColorFilter.tint(iconHoverTint)
                iconTint != Color.Unspecified -> ColorFilter.tint(iconTint)
                else -> null
            }

        Image(
            painter = currentIcon,
            contentDescription = contentDescription,
            colorFilter = colorFilter,
            modifier =
                Modifier
                    .onPointerEvent(PointerEventType.Enter) { hovered = true }
                    .onPointerEvent(PointerEventType.Exit) {
                        hovered = false
                        pressed = false
                    }.onPointerEvent(PointerEventType.Press) { pressed = true }
                    .onPointerEvent(PointerEventType.Release) { pressed = false },
        )
    }
}
