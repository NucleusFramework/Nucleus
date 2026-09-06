package dev.nucleusframework.window.tao.deco

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.TitleBarScope
import dev.nucleusframework.window.WindowControlType
import dev.nucleusframework.window.styling.TitleBarStyle
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.utils.linux.LinuxButtonLayout
import dev.nucleusframework.window.utils.linux.LinuxTitleBarButton
import dev.nucleusframework.window.utils.linux.linuxTitleBarIcons
import dev.nucleusframework.window.utils.linux.rememberLinuxButtonLayout

// Direct copy of `decorated-window-core/WindowControlArea.kt`'s logic, with
// the AWT calls swapped for [TaoWindow] equivalents:
//   `window.dispatchEvent(WINDOW_CLOSING)` → `TaoWindow.requestUserClose()`
//   `frame.extendedState = MAXIMIZED_BOTH/NORMAL`
//                                       → `TaoWindow.setMaximized(true/false)`
//   `frame.extendedState = ICONIFIED`   → `TaoWindow.minimize()`
// Icons / hover / pressed / KDE Y-offset / GNOME button-layout reading are
// reused as-is from `decorated-window-core` so the visual output is byte-for-
// byte identical between the AWT-based backend and the Tao backend.

private val isKde = LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE

// Button sizes match `DecoratedWindowDefaults.{light,dark}TitleBarStyle()`.
// Hardcoded so the Tao tao-sample app doesn't have to wrap in
// `NucleusDecoratedWindowTheme` to get correctly-sized native chrome.
private val LINUX_BUTTON_SIZE: DpSize = if (isKde) DpSize(28.dp, 28.dp) else DpSize(40.dp, 40.dp)

@Suppress("FunctionNaming", "LoopWithTooManyJumpStatements")
@Composable
internal fun TitleBarScope.WindowControlsLinux(
    win: TaoWindow,
    state: DecoratedWindowState,
    isResizable: Boolean,
    style: TitleBarStyle,
    layout: LinuxButtonLayout = rememberLinuxButtonLayout(),
    isFullscreen: Boolean = false,
    onExitFullscreen: (() -> Unit)? = null,
) {
    val icons = linuxTitleBarIcons()
    val buttonAlignment = if (layout.controlsOnRight) Alignment.End else Alignment.Start

    // Iterate over `layout.buttons` in natural order — `layout.buttons[0]` is
    // "closest to the edge". Core's `TitleBarMeasurePolicy` places End items
    // first-declared = rightmost (controls-on-right) and Start items
    // first-declared = leftmost. Mirrors the legacy AWT backend's
    // `WindowControlArea.kt` exactly.
    for (button in layout.buttons) {
        when (button) {
            LinuxTitleBarButton.CLOSE -> {
                val closeHover = if (state.isActive) icons.closeHoverFocused else icons.closeHover
                val closePressed = if (state.isActive) icons.closePressedFocused else icons.closePressed
                LinuxControlButton(
                    onClick = { win.requestUserClose() },
                    icon = icons.close,
                    iconHover = closeHover,
                    iconPressed = closePressed,
                    contentDescription = "Close",
                    style = style,
                    modifier = Modifier.align(buttonAlignment),
                    isCloseButton = true,
                )
            }
            LinuxTitleBarButton.MAXIMIZE -> {
                if (isFullscreen && onExitFullscreen != null) {
                    LinuxControlButton(
                        onClick = onExitFullscreen,
                        icon = icons.maximize,
                        iconHover = icons.maximizeHover,
                        iconPressed = icons.maximizePressed,
                        contentDescription = "Exit fullscreen",
                        style = style,
                        modifier = Modifier.align(buttonAlignment),
                    )
                    continue
                }
                if (!isResizable) continue
                if (state.isMaximized) {
                    LinuxControlButton(
                        onClick = { win.setMaximized(false) },
                        icon = icons.restore,
                        iconHover = icons.restoreHover,
                        iconPressed = icons.restorePressed,
                        contentDescription = "Restore",
                        style = style,
                        modifier = Modifier.align(buttonAlignment),
                    )
                } else {
                    LinuxControlButton(
                        onClick = { win.setMaximized(true) },
                        icon = icons.maximize,
                        iconHover = icons.maximizeHover,
                        iconPressed = icons.maximizePressed,
                        contentDescription = "Maximize",
                        style = style,
                        modifier = Modifier.align(buttonAlignment),
                    )
                }
            }
            LinuxTitleBarButton.MINIMIZE -> {
                LinuxControlButton(
                    onClick = { win.minimize() },
                    icon = icons.minimize,
                    iconHover = icons.minimizeHover,
                    iconPressed = icons.minimizePressed,
                    contentDescription = "Minimize",
                    style = style,
                    modifier = Modifier.align(buttonAlignment),
                )
            }
        }
    }
}

@Suppress("FunctionNaming", "LongParameterList")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LinuxControlButton(
    onClick: () -> Unit,
    icon: Painter,
    iconHover: Painter,
    iconPressed: Painter,
    contentDescription: String,
    style: TitleBarStyle,
    modifier: Modifier = Modifier,
    isCloseButton: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier =
            modifier
                .focusable(false)
                .let { if (isKde) it.offset(y = (-2).dp) else it }
                .size(LINUX_BUTTON_SIZE)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        var hovered by remember { mutableStateOf(false) }
        var pressed by remember { mutableStateOf(false) }

        // Show hover/pressed feedback regardless of window focus — native
        // GNOME and KDE both highlight title-bar controls on hover even when
        // the window is inactive. The focused/unfocused icon *variant* is
        // already chosen at the call site (e.g. closeHover vs
        // closeHoverFocused), so the correct artwork is used either way.
        val currentIcon =
            when {
                pressed -> iconPressed
                hovered -> iconHover
                else -> icon
            }

        // Apply the custom icon tint when set, but skip the close button while
        // hovered/pressed — its artwork has baked-in colors. Mirrors
        // `decorated-window-core/WindowControlArea.kt`.
        val isCloseInteracted = isCloseButton && (hovered || pressed)
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

/**
 * Draws a single GNOME/KDE control button. Shared with the standalone
 * `WindowControls` composable so a scaffold-based window gets the same
 * artwork the [TitleBar]-injected row uses; the button *order* still comes
 * from the desktop's own layout setting.
 */
@Suppress("FunctionNaming")
@Composable
internal fun LinuxWindowControl(
    type: WindowControlType,
    state: DecoratedWindowState,
    style: TitleBarStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val icons = linuxTitleBarIcons()
    when (type) {
        WindowControlType.Close ->
            LinuxControlButton(
                onClick = onClick,
                icon = icons.close,
                iconHover = if (state.isActive) icons.closeHoverFocused else icons.closeHover,
                iconPressed = if (state.isActive) icons.closePressedFocused else icons.closePressed,
                contentDescription = "Close",
                style = style,
                modifier = modifier,
                isCloseButton = true,
            )

        WindowControlType.Minimize ->
            LinuxControlButton(
                onClick = onClick,
                icon = icons.minimize,
                iconHover = icons.minimizeHover,
                iconPressed = icons.minimizePressed,
                contentDescription = "Minimize",
                style = style,
                modifier = modifier,
            )

        WindowControlType.Restore ->
            LinuxControlButton(
                onClick = onClick,
                icon = icons.restore,
                iconHover = icons.restoreHover,
                iconPressed = icons.restorePressed,
                contentDescription = "Restore",
                style = style,
                modifier = modifier,
            )

        // Exit-fullscreen reuses the maximize artwork, like the title-bar row.
        WindowControlType.Maximize, WindowControlType.ExitFullscreen ->
            LinuxControlButton(
                onClick = onClick,
                icon = icons.maximize,
                iconHover = icons.maximizeHover,
                iconPressed = icons.maximizePressed,
                contentDescription =
                    if (type == WindowControlType.ExitFullscreen) "Exit fullscreen" else "Maximize",
                style = style,
                modifier = modifier,
            )
    }
}
