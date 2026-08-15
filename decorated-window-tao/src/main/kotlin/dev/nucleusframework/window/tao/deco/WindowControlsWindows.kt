package dev.nucleusframework.window.tao.deco

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.LocalControlButtonsDirection
import dev.nucleusframework.window.LocalIsDarkTheme
import dev.nucleusframework.window.WindowControlSlot
import dev.nucleusframework.window.WindowControlType
import dev.nucleusframework.window.icons.windows.Close
import dev.nucleusframework.window.icons.windows.CloseDark
import dev.nucleusframework.window.icons.windows.CloseFullscreen
import dev.nucleusframework.window.icons.windows.CloseFullscreenDark
import dev.nucleusframework.window.icons.windows.CloseFullscreenInactive
import dev.nucleusframework.window.icons.windows.CloseFullscreenInactiveDark
import dev.nucleusframework.window.icons.windows.CloseHover
import dev.nucleusframework.window.icons.windows.CloseInactive
import dev.nucleusframework.window.icons.windows.CloseInactiveDark
import dev.nucleusframework.window.icons.windows.Maximize
import dev.nucleusframework.window.icons.windows.MaximizeDark
import dev.nucleusframework.window.icons.windows.MaximizeInactive
import dev.nucleusframework.window.icons.windows.MaximizeInactiveDark
import dev.nucleusframework.window.icons.windows.Minimize
import dev.nucleusframework.window.icons.windows.MinimizeDark
import dev.nucleusframework.window.icons.windows.MinimizeInactive
import dev.nucleusframework.window.icons.windows.MinimizeInactiveDark
import dev.nucleusframework.window.icons.windows.Restore
import dev.nucleusframework.window.icons.windows.RestoreDark
import dev.nucleusframework.window.icons.windows.RestoreInactive
import dev.nucleusframework.window.icons.windows.RestoreInactiveDark
import dev.nucleusframework.window.icons.windows.WindowsControlButtonIcons
import dev.nucleusframework.window.internal.WindowsCaptionButtonStyle
import dev.nucleusframework.window.internal.animateWindowsCaptionColor
import dev.nucleusframework.window.internal.windowsCaptionButtonBackground
import dev.nucleusframework.window.resolveWindowControl
import dev.nucleusframework.window.styling.TitleBarStyle
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.TaoWindow

// Mirrors `decorated-window-awt/WindowsWindowControlArea.kt` so the visual
// output is identical between the AWT-based backend and the Tao backend.

private val WINDOWS_BUTTON_WIDTH = 46.dp

private const val CLOSE_HOVER_ALPHA_EPSILON = 0.02f

/**
 * Windows-style window controls (minimize / maximize-restore / close).
 *
 * Auto-injected by [TitleBar] when running on Windows; library users do not
 * need to call it directly. The visual output mirrors
 * `decorated-window-core`'s `WindowsWindowControlArea` (same icon set, same
 * hover/pressed colors, same active/inactive variants) so the two backends
 * stay visually consistent.
 *
 * Drawn and handled entirely in Compose, but each button publishes its rect
 * to the native hit-test ([CaptionButtonHitZones]) so `WM_NCHITTEST` can
 * answer `HTMINBUTTON`/`HTMAXBUTTON`/`HTCLOSE` over it — which is what makes
 * Windows 11 show the Snap Layouts flyout on maximize hover. The native side
 * forwards the resulting NC clicks back as client messages, so hover, press
 * and click still run through the Compose handlers below.
 */
@Suppress("FunctionNaming")
@Composable
internal fun WindowControlsWindows(
    win: TaoWindow,
    state: DecoratedWindowState,
    style: TitleBarStyle,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
    onExitFullscreen: (() -> Unit)? = null,
) {
    // Match decorated-window-jni's WindowsWindowControlArea: LTR renders
    // Minimize/Maximize/Close, RTL mirrors it to Close/Maximize/Minimize.
    CompositionLocalProvider(LocalLayoutDirection provides LocalControlButtonsDirection.current) {
        Row(modifier = modifier.fillMaxHeight()) {
            for (slot in WindowControlSlot.entries) {
                val action = resolveWindowControl(slot, win, state, isFullscreen, onExitFullscreen)
                if (action != null) WindowsWindowControl(action.type, state, style, action.onClick)
            }
        }
    }
}

/**
 * Draws a single Fluent caption button. Shared by the [TitleBar]-injected
 * [WindowControlsWindows] row and the standalone `WindowControls` composable,
 * so both paths are pixel-identical.
 */
@Suppress("FunctionNaming")
@Composable
internal fun WindowsWindowControl(
    type: WindowControlType,
    state: DecoratedWindowState,
    style: TitleBarStyle,
    onClick: () -> Unit,
) {
    val isDark = LocalIsDarkTheme.current
    val isCloseButton = type == WindowControlType.Close
    val window = LocalTaoWindow.current

    // Deliberately no background of their own: under a backdrop the buttons
    // sit directly on the material, exactly like Windows 11's native caption
    // buttons on Mica — glyph and hover overlay only.

    // Feed the button's window-space rect to the native hit-test so hovering
    // maximize shows the Windows 11 Snap Layouts flyout; clear it when the
    // button leaves the composition.
    val positionModifier =
        if (window != null) {
            Modifier.onGloballyPositioned { coordinates ->
                CaptionButtonHitZones.publish(window, type, coordinates.boundsInWindow())
            }
        } else {
            Modifier
        }
    if (window != null) {
        DisposableEffect(window, type) {
            onDispose { CaptionButtonHitZones.publish(window, type, null) }
        }
    }

    WindowsCaptionButton(
        onClick = onClick,
        isDark = isDark,
        style = style,
        icon = windowsControlIcon(type, active = state.isActive, isDark = isDark),
        contentDescription = windowsControlDescription(type),
        iconHover = if (isCloseButton) WindowsControlButtonIcons.CloseHover else null,
        isCloseButton = isCloseButton,
        modifier = positionModifier,
    )
}

/**
 * Icon artwork per control, in the four active/inactive x light/dark variants
 * `decorated-window-core`'s `WindowsWindowControlArea` uses. Exit-fullscreen
 * has its own set (the "collapse" glyph), matching decorated-window-jni.
 */
private fun windowsControlIcon(
    type: WindowControlType,
    active: Boolean,
    isDark: Boolean,
): ImageVector =
    when (type) {
        WindowControlType.Minimize ->
            pickVariant(
                active,
                isDark,
                WindowsControlButtonIcons.Minimize,
                WindowsControlButtonIcons.MinimizeDark,
                WindowsControlButtonIcons.MinimizeInactive,
                WindowsControlButtonIcons.MinimizeInactiveDark,
            )

        WindowControlType.Maximize ->
            pickVariant(
                active,
                isDark,
                WindowsControlButtonIcons.Maximize,
                WindowsControlButtonIcons.MaximizeDark,
                WindowsControlButtonIcons.MaximizeInactive,
                WindowsControlButtonIcons.MaximizeInactiveDark,
            )

        WindowControlType.Restore ->
            pickVariant(
                active,
                isDark,
                WindowsControlButtonIcons.Restore,
                WindowsControlButtonIcons.RestoreDark,
                WindowsControlButtonIcons.RestoreInactive,
                WindowsControlButtonIcons.RestoreInactiveDark,
            )

        WindowControlType.ExitFullscreen ->
            pickVariant(
                active,
                isDark,
                WindowsControlButtonIcons.CloseFullscreen,
                WindowsControlButtonIcons.CloseFullscreenDark,
                WindowsControlButtonIcons.CloseFullscreenInactive,
                WindowsControlButtonIcons.CloseFullscreenInactiveDark,
            )

        WindowControlType.Close ->
            pickVariant(
                active,
                isDark,
                WindowsControlButtonIcons.Close,
                WindowsControlButtonIcons.CloseDark,
                WindowsControlButtonIcons.CloseInactive,
                WindowsControlButtonIcons.CloseInactiveDark,
            )
    }

@Suppress("LongParameterList")
private fun pickVariant(
    active: Boolean,
    isDark: Boolean,
    light: ImageVector,
    dark: ImageVector,
    inactiveLight: ImageVector,
    inactiveDark: ImageVector,
): ImageVector =
    if (active) {
        if (isDark) dark else light
    } else {
        if (isDark) inactiveDark else inactiveLight
    }

private fun windowsControlDescription(type: WindowControlType): String =
    when (type) {
        WindowControlType.Minimize -> "Minimize"
        WindowControlType.Maximize -> "Maximize"
        WindowControlType.Restore -> "Restore"
        WindowControlType.Close -> "Close"
        WindowControlType.ExitFullscreen -> "Exit fullscreen"
    }

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
private fun WindowsCaptionButton(
    onClick: () -> Unit,
    isDark: Boolean,
    style: TitleBarStyle,
    icon: ImageVector,
    contentDescription: String,
    iconHover: ImageVector? = null,
    isCloseButton: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val appearing = hovered || pressed

    val targetBackground =
        windowsCaptionButtonBackground(
            hovered = hovered,
            pressed = pressed,
            isCloseButton = isCloseButton,
            isDark = isDark,
            customHover = style.colors.iconButtonHoveredBackground,
            customPressed = style.colors.iconButtonPressedBackground,
        )
    val backgroundColor =
        animateWindowsCaptionColor(
            targetBackground,
            appearing = appearing,
            durationMillis = WindowsCaptionButtonStyle.BackgroundFadeOutMillis,
        )

    // Keep the white close glyph while the red fill is still fading out so
    // the X does not snap back to gray on a still-red background.
    val isCloseHovered =
        isCloseButton &&
            (appearing || backgroundColor.alpha > CLOSE_HOVER_ALPHA_EPSILON)
    val currentIcon: Painter =
        rememberVectorPainter(
            if (isCloseHovered && iconHover != null) iconHover else icon,
        )

    val colorFilter =
        captionButtonColorFilter(
            hovered = hovered,
            pressed = pressed,
            isCloseHovered = isCloseHovered,
            style = style,
        )

    Box(
        modifier =
            modifier
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
        Image(painter = currentIcon, contentDescription = contentDescription, colorFilter = colorFilter)
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
        // Close hover swaps to the baked-red close artwork; don't tint it.
        isCloseHovered -> null
        (hovered || pressed) && iconHoverTint != Color.Unspecified ->
            ColorFilter.tint(iconHoverTint)
        iconTint != Color.Unspecified -> ColorFilter.tint(iconTint)
        else -> null
    }
}
