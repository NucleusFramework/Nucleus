package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.kdroidfilter.nucleus.window.hasMacOSLargeCornerRadius
import io.github.kdroidfilter.nucleus.window.hasNewFullscreenControls
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.tao.LocalRequestedTitleBarHeight
import io.github.kdroidfilter.nucleus.window.tao.NativeMetalBridge
import io.github.kdroidfilter.nucleus.window.tao.NativeTaoBridge
import io.github.kdroidfilter.nucleus.window.tao.TaoDecoratedWindowScope
import io.github.kdroidfilter.nucleus.window.tao.TaoWindow
import io.github.kdroidfilter.nucleus.window.tao.WindowControlsLinux
import io.github.kdroidfilter.nucleus.window.tao.WindowControlsWindows
import io.github.kdroidfilter.nucleus.window.utils.linux.rememberLinuxButtonLayout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

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
    // Tao always provides a [TaoDecoratedWindowScope] at runtime — cast so the
    // public extension can stay on the abstract `core.DecoratedWindowScope`
    // (drop-in compatible with jbr / jni call sites).
    val taoScope = this as TaoDecoratedWindowScope
    val taoWindow = taoScope.window
    val currentState = taoScope.state

    // Publish the resolved height up to DecoratedWindow, which applies the
    // native button-centering constraints once the window is shown.
    val heightHolder = LocalRequestedTitleBarHeight.current

    // Read parity-only modifier flags (consumed for swap-in API parity with
    // jbr/jni; tao's macOS path already animates the menu-bar offset and
    // applies the large corner radius via `MacOSStyle` at window creation).
    @Suppress("UNUSED_VARIABLE")
    val newFullscreenControls = modifier.hasNewFullscreenControls()
    @Suppress("UNUSED_VARIABLE")
    val macOSLargeCornerRadius = modifier.hasMacOSLargeCornerRadius()

    val linuxLayout = if (Platform.Current == Platform.Linux) rememberLinuxButtonLayout() else null
    val controlDir = controlButtonsDirection.resolve()
    val controlIsRtl = controlDir == LayoutDirection.Rtl

    // macOS: flip the AppKit traffic-lights to the right edge when RTL is
    // active. Mirrors `decorated-window-jni`'s `nativeSetRTL` call path.
    if (Platform.Current == Platform.MacOS) {
        LaunchedEffect(taoWindow, controlIsRtl) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
            if (nsView != 0L && NativeMetalBridge.isLoaded) {
                NativeMetalBridge.nativeSetButtonLayoutRtl(nsView, controlIsRtl)
            }
        }
    }

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
        applyTitleBar = { measuredHeight, titleBarState ->
            heightHolder.value = measuredHeight.value
            titleBarPadding(
                measuredHeight = measuredHeight,
                isFullscreen = titleBarState.isFullscreen,
                controlIsRtl = controlDir == LayoutDirection.Rtl,
                linuxControlsOnRight = linuxLayout?.controlsOnRight,
            )
        },
        backgroundContent = backgroundContent,
        content = { titleBarState ->
            // Window controls are declared BEFORE user content so core's
            // [TitleBarMeasurePolicy] places them at the extreme edge first
            // (first-declared End item = rightmost in LTR; first-declared
            // Start item = leftmost). Mirrors `decorated-window-jni`'s
            // TitleBar.{Linux,Windows}.kt where WindowControlArea is invoked
            // ahead of `content()`.
            when (Platform.Current) {
                Platform.Linux -> if (linuxLayout != null) {
                    WindowControlsLinux(
                        win = taoWindow,
                        state = titleBarState,
                        isResizable = taoWindow.isResizable,
                        layout = linuxLayout,
                    )
                }
                Platform.Windows -> WindowControlsWindows(
                    win = taoWindow,
                    state = titleBarState,
                    modifier = Modifier.align(Alignment.End),
                )
                else -> Unit // macOS uses native AppKit traffic-lights
            }

            content(titleBarState)
        },
    )
}

/**
 * Platform-specific reservation insets returned to [GenericTitleBarImpl]'s
 * `applyTitleBar` callback. Mirrors `decorated-window-jni`'s `MacOSTitleBar`
 * exactly:
 * - macOS in fullscreen: 80 dp on the controls edge.
 * - macOS otherwise: Apple's traffic-light formula
 *   `2 * leftMargin + 2 * shrink * 20`, where `leftMargin = min(h/2, 20)` and
 *   `shrink = min(h/28, 1)`. At the default 40 dp height this gives 80 dp.
 * - KDE Breeze: 4 dp on the controls side.
 */
internal fun titleBarPadding(
    measuredHeight: Dp,
    isFullscreen: Boolean,
    controlIsRtl: Boolean,
    linuxControlsOnRight: Boolean?,
): PaddingValues =
    when (Platform.Current) {
        Platform.MacOS -> {
            val inset = if (isFullscreen) {
                MAC_FULLSCREEN_BUTTONS_INSET
            } else {
                macTrafficLightInset(measuredHeight)
            }
            if (controlIsRtl) PaddingValues(end = inset) else PaddingValues(start = inset)
        }
        Platform.Linux -> {
            if (isLinuxKde && linuxControlsOnRight != null) {
                if (linuxControlsOnRight) {
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

private val MAC_FULLSCREEN_BUTTONS_INSET: Dp = 80.dp

@Suppress("MagicNumber")
private fun macTrafficLightInset(height: Dp): Dp {
    val h = height.value
    val shrink = minOf(h / 28f, 1f)
    val leftMargin = minOf(h / 2f, 20f)
    return (2f * leftMargin + 2f * shrink * 20f).dp
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
        // Robust double-tap detection: only treat a press as the second tap of a
        // double-tap if a Release was actually observed between the two presses.
        // On macOS, `dragWindow()` enters a native modal event-tracking loop that
        // can swallow the trailing release; without this guard, the next press
        // (after the user lifts and clicks again) would be misclassified as a
        // double-tap and falsely toggle maximize → fullscreen.
        val ctx = currentCoroutineContext()
        var lastPress = 0L
        var releasedSinceLastPress = true
        awaitPointerEventScope {
            while (ctx.isActive) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Press -> if (!change.isConsumed) {
                        change.consume()
                        val now = System.currentTimeMillis()
                        val isDoubleTap = releasedSinceLastPress &&
                            now - lastPress in doubleTapMinMs..doubleTapMaxMs
                        if (isDoubleTap) {
                            window.setMaximized(!window.isMaximized)
                            lastPress = 0L
                        } else {
                            window.dragWindow()
                            lastPress = now
                        }
                        releasedSinceLastPress = false
                    }
                    PointerEventType.Release -> releasedSinceLastPress = true
                    else -> Unit
                }
            }
        }
    }
