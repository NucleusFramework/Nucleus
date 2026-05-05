package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.ControlButtonsDirection
import io.github.kdroidfilter.nucleus.window.DecoratedDialogState
import io.github.kdroidfilter.nucleus.window.GenericTitleBarImpl
import io.github.kdroidfilter.nucleus.window.LocalControlButtonsDirection
import io.github.kdroidfilter.nucleus.window.TitleBarScope
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.tao.LocalRequestedTitleBarHeight
import io.github.kdroidfilter.nucleus.window.tao.NativeMetalBridge
import io.github.kdroidfilter.nucleus.window.tao.NativeTaoBridge
import io.github.kdroidfilter.nucleus.window.tao.TaoDecoratedDialogScope
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
    content: @Composable TitleBarScope.(DecoratedDialogState) -> Unit = {},
) {
    val taoScope = this as TaoDecoratedDialogScope
    val taoWindow = taoScope.window
    val dialogState = taoScope.state
    val windowState = dialogState.toDecoratedWindowState()
    val controlDir = controlButtonsDirection.resolve()
    val controlIsRtl = controlDir == LayoutDirection.Rtl

    // Publish the resolved height up to DecoratedWindow so its native
    // button-centering call uses the dialog's actual title-bar height.
    val heightHolder = LocalRequestedTitleBarHeight.current
    SideEffect { heightHolder.value = style.metrics.height.value }

    // macOS: flip the AppKit traffic-lights to the right edge when RTL is
    // active. Same path as the windowed [TitleBar].
    if (Platform.Current == Platform.MacOS) {
        LaunchedEffect(taoWindow, controlIsRtl) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
            if (nsView != 0L && NativeMetalBridge.isLoaded) {
                NativeMetalBridge.nativeSetButtonLayoutRtl(nsView, controlIsRtl)
            }
        }
    }

    GenericTitleBarImpl(
        state = windowState,
        modifier = modifier.titleBarHitTestHandler(taoWindow),
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlDir,
        applyTitleBar = { measuredHeight, titleBarState ->
            heightHolder.value = measuredHeight.value
            // Identical reservation logic to [TitleBar]: macOS traffic-lights
            // (with fullscreen 80 dp override), KDE Breeze edge padding, none
            // elsewhere. Dialogs never expose a Linux native control layout
            // (close-only chrome), so [linuxControlsOnRight] = null.
            titleBarPadding(
                measuredHeight = measuredHeight,
                isFullscreen = titleBarState.isFullscreen,
                controlIsRtl = controlIsRtl,
                linuxControlsOnRight = null,
            )
        },
        content = { _ ->
            // Window controls are declared BEFORE the user content so core's
            // [TitleBarMeasurePolicy] places them at the extreme edge first
            // — same convention as [TitleBar].
            when (Platform.Current) {
                Platform.Windows ->
                    DialogWindowsCloseButton(
                        onClick = { taoWindow.requestUserClose() },
                        modifier = Modifier.align(Alignment.End),
                        style = style,
                    )
                Platform.Linux ->
                    DialogLinuxCloseButton(
                        onClick = { taoWindow.requestUserClose() },
                        state = windowState,
                        style = style,
                        modifier = Modifier.align(Alignment.End),
                    )
                else -> Unit // macOS uses native AppKit traffic-lights
            }

            content(dialogState)
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
            modifier =
                modifier
                    .focusable(false)
                    .size(WINDOWS_DLG_BUTTON_WIDTH, style.metrics.height)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    ).onPointerEvent(PointerEventType.Enter) { hovered = true }
                    .onPointerEvent(PointerEventType.Exit) {
                        hovered = false
                        pressed = false
                    }.onPointerEvent(PointerEventType.Press) { pressed = true }
                    .onPointerEvent(PointerEventType.Release) { pressed = false },
            contentAlignment = Alignment.Center,
        ) {
            val bg =
                when {
                    pressed -> WindowsCloseButtonPressed
                    hovered -> WindowsCloseButtonHovered
                    else -> Color.Transparent
                }
            Box(
                modifier =
                    Modifier
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
            modifier =
                modifier
                    .focusable(false)
                    .let {
                        if (isKdeDlg) {
                            it.size(
                                style.metrics.titlePaneButtonSize,
                            )
                        } else {
                            it.size(style.metrics.titlePaneButtonSize)
                        }
                    }.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    ).onPointerEvent(PointerEventType.Enter) { hovered = true }
                    .onPointerEvent(PointerEventType.Exit) {
                        hovered = false
                        pressed = false
                    }.onPointerEvent(PointerEventType.Press) { pressed = true }
                    .onPointerEvent(PointerEventType.Release) { pressed = false },
            contentAlignment = Alignment.Center,
        ) {
            val isCloseInteracted = hovered || pressed
            val currentIcon =
                when {
                    pressed && (state.isActive || isKdeDlg) -> closePressed
                    hovered && (state.isActive || isKdeDlg) -> closeHover
                    else -> icons.close
                }
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
                contentDescription = "Close",
                colorFilter = colorFilter,
            )
        }
    }
}
