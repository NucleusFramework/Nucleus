package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.ControlButtonsDirection
import io.github.kdroidfilter.nucleus.window.DecoratedWindowState
import io.github.kdroidfilter.nucleus.window.GenericTitleBarImpl
import io.github.kdroidfilter.nucleus.window.TitleBarScope
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.linux.rememberLinuxButtonLayout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

// Native traffic-lights occupy roughly the leftmost 78 points on macOS.
private val NATIVE_BUTTONS_INSET_MACOS: Dp = 78.dp

// KDE breeze gives the leading edge of its title bar a small padding so the
// edge-most window control button doesn't sit flush against the window border.
// Mirrors `decorated-window-core/TitleBarLinuxCommon.kt::kdePaddingForButtonLayout`.
private val LINUX_KDE_EDGE_PADDING: Dp = 4.dp
private val isLinuxKde: Boolean =
    Platform.Current == Platform.Linux &&
        LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE

/**
 * Platform-aware title bar for the Tao-backed [DecoratedWindow].
 *
 * Signature mirrors `decorated-window-jbr` / `decorated-window-jni` so an app
 * can swap backends without touching call sites:
 * - [gradientStartColor] enables the optional centered horizontal gradient.
 * - [style] resolves all metrics + colors via [LocalTitleBarStyle]; the default
 *   theme drives the bar height, content color, and gradient bounds.
 * - [controlButtonsDirection] flips the system control buttons to the other
 *   side independently of the content's [LocalLayoutDirection].
 * - [backgroundContent] is rendered behind the content layer (full bleed).
 *
 * Tao-specific behavior preserved on top of the canonical contract:
 * - `windowDragHandler` consumes title-bar press events and dispatches them to
 *   `TaoWindow.dragWindow()`, with double-press → toggle-maximize.
 * - macOS native traffic-light area is reserved via [PaddingValues] (78 dp on
 *   each side), matching `decorated-window-jni`'s JBR-driven inset path.
 * - KDE breeze 4 dp edge padding applied on the controls side.
 * - Linux + Windows control buttons are injected here (no native chrome).
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun DecoratedWindowScope.TitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    val taoWindow = window
    val currentState = state

    // Publish the resolved height up to DecoratedWindow, which applies the
    // native button-centering constraints once the window is shown.
    val heightHolder = LocalRequestedTitleBarHeight.current

    val linuxLayout = if (Platform.Current == Platform.Linux) rememberLinuxButtonLayout() else null
    val controlDir = controlButtonsDirection.resolve()

    val viewConfig = LocalViewConfiguration.current

    GenericTitleBarImpl(
        state = currentState,
        modifier = modifier.windowDragHandler(
            window = taoWindow,
            doubleTapMinMs = viewConfig.doubleTapMinTimeMillis,
            doubleTapMaxMs = viewConfig.doubleTapTimeoutMillis,
        ),
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlDir,
        applyTitleBar = { measuredHeight, _ ->
            heightHolder.value = measuredHeight.value
            titleBarPadding(linuxLayout?.controlsOnRight)
        },
        backgroundContent = backgroundContent,
        content = { titleBarState ->
            // Linux + controls-on-left (rare KDE setup): controls placed via
            // Modifier.align(Alignment.Start). Declare BEFORE user content so
            // they appear at the start edge.
            if (linuxLayout != null && !linuxLayout.controlsOnRight) {
                WindowControlsLinux(
                    win = taoWindow,
                    state = titleBarState,
                    isResizable = taoWindow.isResizable,
                    layout = linuxLayout,
                )
            }

            content(titleBarState)

            // Windows: native min/max/close are not painted by DWM (the
            // WndProc subclass returns HTCLIENT for the title bar zone),
            // so the library injects its own Compose buttons here. The
            // user does not see them on macOS, where AppKit traffic-light
            // buttons are positioned by `nativeApplyButtonLayout`.
            if (Platform.Current == Platform.Windows) {
                WindowControlsWindows(
                    win = taoWindow,
                    state = titleBarState,
                    modifier = Modifier.align(Alignment.End),
                )
            }

            // Linux + controls-on-right (default).
            if (linuxLayout != null && linuxLayout.controlsOnRight) {
                WindowControlsLinux(
                    win = taoWindow,
                    state = titleBarState,
                    isResizable = taoWindow.isResizable,
                    layout = linuxLayout,
                )
            }
        },
    )
}

/**
 * Platform-specific reservation insets returned to [GenericTitleBarImpl]'s
 * `applyTitleBar` callback. macOS reserves space for the native AppKit
 * traffic-light buttons; KDE Breeze pads the controls side to keep the
 * edge-most button off the window border.
 */
private fun titleBarPadding(controlsOnRight: Boolean?): PaddingValues =
    when (Platform.Current) {
        Platform.MacOS -> PaddingValues(start = NATIVE_BUTTONS_INSET_MACOS, end = NATIVE_BUTTONS_INSET_MACOS)
        Platform.Linux -> {
            if (isLinuxKde && controlsOnRight != null) {
                if (controlsOnRight) {
                    PaddingValues(end = LINUX_KDE_EDGE_PADDING)
                } else {
                    PaddingValues(start = LINUX_KDE_EDGE_PADDING)
                }
            } else {
                PaddingValues(0.dp)
            }
        }
        else -> PaddingValues(0.dp)
    }

// ── Drag ──────────────────────────────────────────────────────────────────

private fun Modifier.windowDragHandler(
    window: TaoWindow,
    doubleTapMinMs: Long,
    doubleTapMaxMs: Long,
): Modifier =
    pointerInput(window) {
        // We always consume Press events on the title bar to dispatch them to
        // `dragWindow()` (Tao posts the platform-specific drag-start message).
        // Because we consume, the native window machinery never sees the
        // sequence as a real title-bar click → its native double-click→zoom
        // (macOS) or →maximize (Windows) doesn't fire. Detect it in Compose
        // and toggle maximize ourselves on every platform. Mirrors
        // `decorated-window-jni`'s `TitleBar.{MacOS,Windows}.kt`.
        val ctx = currentCoroutineContext()
        var lastPress = 0L
        awaitPointerEventScope {
            while (ctx.isActive) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: continue
                if (event.type == PointerEventType.Press && !change.isConsumed) {
                    change.consume()
                    val now = System.currentTimeMillis()
                    if (now - lastPress in doubleTapMinMs..doubleTapMaxMs) {
                        window.setMaximized(!window.isMaximized)
                        lastPress = 0L
                    } else {
                        window.dragWindow()
                        lastPress = now
                    }
                }
            }
        }
    }
