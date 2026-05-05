package io.github.kdroidfilter.nucleus.window.tao

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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.window.DecoratedWindowState
import io.github.kdroidfilter.nucleus.window.TitleBarScope
import io.github.kdroidfilter.nucleus.window.utils.linux.LinuxButtonLayout
import io.github.kdroidfilter.nucleus.window.utils.linux.LinuxTitleBarButton
import io.github.kdroidfilter.nucleus.window.utils.linux.linuxTitleBarIcons
import io.github.kdroidfilter.nucleus.window.utils.linux.rememberLinuxButtonLayout

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

@Suppress("FunctionNaming")
@Composable
internal fun TitleBarScope.WindowControlsLinux(
    win: TaoWindow,
    state: DecoratedWindowState,
    isResizable: Boolean,
    layout: LinuxButtonLayout = rememberLinuxButtonLayout(),
    isFullscreen: Boolean = false,
    onExitFullscreen: (() -> Unit)? = null,
) {
    val icons = linuxTitleBarIcons()
    val buttonAlignment = if (layout.controlsOnRight) Alignment.End else Alignment.Start

    // Iterate over `layout.buttons` in natural order — `layout.buttons[0]` is
    // "closest to the edge". Core's `TitleBarMeasurePolicy` places End items
    // first-declared = rightmost (controls-on-right) and Start items
    // first-declared = leftmost. Mirrors `decorated-window-jni`'s
    // `WindowControlArea.kt` exactly.
    for (button in layout.buttons) {
        when (button) {
            LinuxTitleBarButton.CLOSE -> {
                val closeHover = if (state.isActive) icons.closeHoverFocused else icons.closeHover
                val closePressed = if (state.isActive) icons.closePressedFocused else icons.closePressed
                LinuxControlButton(
                    onClick = { win.requestUserClose() },
                    state = state,
                    icon = icons.close,
                    iconHover = closeHover,
                    iconPressed = closePressed,
                    contentDescription = "Close",
                    alignment = buttonAlignment,
                    isCloseButton = true,
                )
            }
            LinuxTitleBarButton.MAXIMIZE -> {
                if (isFullscreen && onExitFullscreen != null) {
                    LinuxControlButton(
                        onClick = onExitFullscreen,
                        state = state,
                        icon = icons.maximize,
                        iconHover = icons.maximizeHover,
                        iconPressed = icons.maximizePressed,
                        contentDescription = "Exit fullscreen",
                        alignment = buttonAlignment,
                    )
                    continue
                }
                if (!isResizable) continue
                if (state.isMaximized) {
                    LinuxControlButton(
                        onClick = { win.setMaximized(false) },
                        state = state,
                        icon = icons.restore,
                        iconHover = icons.restoreHover,
                        iconPressed = icons.restorePressed,
                        contentDescription = "Restore",
                        alignment = buttonAlignment,
                    )
                } else {
                    LinuxControlButton(
                        onClick = { win.setMaximized(true) },
                        state = state,
                        icon = icons.maximize,
                        iconHover = icons.maximizeHover,
                        iconPressed = icons.maximizePressed,
                        contentDescription = "Maximize",
                        alignment = buttonAlignment,
                    )
                }
            }
            LinuxTitleBarButton.MINIMIZE -> {
                LinuxControlButton(
                    onClick = { win.minimize() },
                    state = state,
                    icon = icons.minimize,
                    iconHover = icons.minimizeHover,
                    iconPressed = icons.minimizePressed,
                    contentDescription = "Minimize",
                    alignment = buttonAlignment,
                )
            }
        }
    }
}

@Suppress("FunctionNaming", "LongParameterList")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TitleBarScope.LinuxControlButton(
    onClick: () -> Unit,
    state: DecoratedWindowState,
    icon: Painter,
    iconHover: Painter,
    iconPressed: Painter,
    contentDescription: String,
    alignment: Alignment.Horizontal,
    isCloseButton: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier =
            Modifier
                .align(alignment)
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

        // Mirrors core/WindowControlArea: KDE keeps hover/pressed icons even
        // when the window is inactive (its inactive theme has the same shape),
        // GNOME only when active.
        val currentIcon =
            when {
                pressed && (state.isActive || isKde) -> iconPressed
                hovered && (state.isActive || isKde) -> iconHover
                else -> icon
            }

        Image(
            painter = currentIcon,
            contentDescription = contentDescription,
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
