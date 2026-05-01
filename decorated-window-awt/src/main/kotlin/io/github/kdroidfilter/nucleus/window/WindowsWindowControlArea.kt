package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
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
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.windows.windowsTitleBarIcons
import java.awt.Frame
import java.awt.event.WindowEvent

private val WINDOWS_BUTTON_WIDTH = 46.dp

// Fixed Windows-native button colors — never theme-dependent
@Suppress("MagicNumber")
private val WindowsButtonHoveredLight = Color(0x1A000000)

@Suppress("MagicNumber")
private val WindowsButtonHoveredDark = Color(0x1AFFFFFF)

@Suppress("MagicNumber")
private val WindowsButtonPressedLight = Color(0x33000000)

@Suppress("MagicNumber")
private val WindowsButtonPressedDark = Color(0x33FFFFFF)

@Suppress("MagicNumber")
private val WindowsCloseButtonHovered = Color(0xFFE81123)

@Suppress("MagicNumber")
private val WindowsCloseButtonPressed = Color(0xFFF1707A)

@Suppress("FunctionNaming")
@Composable
fun TitleBarScope.WindowsWindowControlArea(
    window: java.awt.Window,
    state: DecoratedWindowState,
    style: TitleBarStyle,
    isFullscreen: Boolean = false,
    onExitFullscreen: (() -> Unit)? = null,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LocalControlButtonsDirection.current) {
        val icons = windowsTitleBarIcons()

        // Close button (placed first with Alignment.End, so it's rightmost)
        WindowsCaptionButton(
            onClick = { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) },
            state = state,
            style = style,
            icon = if (state.isActive) icons.close else icons.closeInactive,
            iconHover = icons.closeHover,
            contentDescription = "Close",
            isCloseButton = true,
        )

        // In fullscreen: show exit-fullscreen button instead of maximize/restore
        if (isFullscreen && onExitFullscreen != null) {
            WindowsCaptionButton(
                onClick = onExitFullscreen,
                state = state,
                style = style,
                icon = if (state.isActive) icons.exitFullscreen else icons.exitFullscreenInactive,
                contentDescription = "Exit fullscreen",
            )
        } else {
            // Maximize/Restore button (only if resizable)
            val frame = window as? Frame
            if (frame != null && frame.isResizable) {
                if (state.isMaximized) {
                    WindowsCaptionButton(
                        onClick = { frame.extendedState = Frame.NORMAL },
                        state = state,
                        style = style,
                        icon = if (state.isActive) icons.restore else icons.restoreInactive,
                        contentDescription = "Restore",
                    )
                } else {
                    WindowsCaptionButton(
                        onClick = { frame.extendedState = Frame.MAXIMIZED_BOTH },
                        state = state,
                        style = style,
                        icon = if (state.isActive) icons.maximize else icons.maximizeInactive,
                        contentDescription = "Maximize",
                    )
                }
            }
        }

        // Minimize button
        WindowsCaptionButton(
            onClick = {
                (window as? Frame)?.let {
                    it.extendedState = it.extendedState or Frame.ICONIFIED
                }
            },
            state = state,
            style = style,
            icon = if (state.isActive) icons.minimize else icons.minimizeInactive,
            contentDescription = "Minimize",
        )
    }
}

/**
 * Close button for dialog title bars on Windows.
 * Unlike [WindowsWindowControlArea], this only shows the close button.
 */
@Suppress("FunctionNaming")
@Composable
fun TitleBarScope.WindowsDialogCloseButton(
    window: java.awt.Window,
    state: DecoratedDialogState,
    style: TitleBarStyle,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LocalControlButtonsDirection.current) {
        val icons = windowsTitleBarIcons()
        val windowState = state.toDecoratedWindowState()

        WindowsCaptionButton(
            onClick = { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) },
            state = windowState,
            style = style,
            icon = if (windowState.isActive) icons.close else icons.closeInactive,
            iconHover = icons.closeHover,
            contentDescription = "Close",
            isCloseButton = true,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming", "LongParameterList", "UnusedParameter", "CyclomaticComplexMethod")
@Composable
private fun TitleBarScope.WindowsCaptionButton(
    onClick: () -> Unit,
    state: DecoratedWindowState,
    style: TitleBarStyle,
    icon: Painter,
    contentDescription: String,
    iconHover: Painter? = null,
    isCloseButton: Boolean = false,
) {
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }

    val isDark = LocalIsDarkTheme.current
    val backgroundColor =
        captionButtonBackground(
            hovered = hovered,
            pressed = pressed,
            isCloseButton = isCloseButton,
            isDark = isDark,
            style = style,
        )

    val isCloseHovered = (hovered || pressed) && isCloseButton
    val currentIcon =
        when {
            isCloseHovered && iconHover != null -> iconHover
            else -> icon
        }

    val colorFilter =
        captionButtonColorFilter(
            hovered = hovered,
            pressed = pressed,
            isCloseHovered = isCloseHovered,
            style = style,
        )

    Box(
        modifier =
            Modifier
                .align(Alignment.End)
                .focusable(false)
                .fillMaxHeight()
                .width(WINDOWS_BUTTON_WIDTH)
                .background(backgroundColor)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) {
                    hovered = false
                    pressed = false
                }.onPointerEvent(PointerEventType.Press) { pressed = true }
                .onPointerEvent(PointerEventType.Release) { pressed = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = currentIcon,
            contentDescription = contentDescription,
            colorFilter = colorFilter,
        )
    }
}

private fun captionButtonBackground(
    hovered: Boolean,
    pressed: Boolean,
    isCloseButton: Boolean,
    isDark: Boolean,
    style: TitleBarStyle,
): Color {
    val customHover = style.colors.iconButtonHoveredBackground
    val customPressed = style.colors.iconButtonPressedBackground
    val pressedColor =
        customPressed.takeUnless { it == Color.Transparent }
            ?: if (isDark) WindowsButtonPressedDark else WindowsButtonPressedLight
    val hoveredColor =
        customHover.takeUnless { it == Color.Transparent }
            ?: if (isDark) WindowsButtonHoveredDark else WindowsButtonHoveredLight
    return when {
        pressed && isCloseButton -> WindowsCloseButtonPressed
        pressed -> pressedColor
        hovered && isCloseButton -> WindowsCloseButtonHovered
        hovered -> hoveredColor
        else -> Color.Transparent
    }
}

private fun captionButtonColorFilter(
    hovered: Boolean,
    pressed: Boolean,
    isCloseHovered: Boolean,
    style: TitleBarStyle,
): ColorFilter? {
    val iconTint = style.colors.controlButtonIconColor
    val iconHoverTint = style.colors.controlButtonIconHoverColor
    return when {
        isCloseHovered -> null
        (hovered || pressed) && iconHoverTint != Color.Unspecified ->
            ColorFilter.tint(iconHoverTint)
        iconTint != Color.Unspecified -> ColorFilter.tint(iconTint)
        else -> null
    }
}
