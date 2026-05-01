package io.github.kdroidfilter.nucleus.window

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.internal.InstallMinimumSizeAfterCentering
import io.github.kdroidfilter.nucleus.window.internal.inflateToMinimumSize
import io.github.kdroidfilter.nucleus.window.utils.linux.JniLinuxWindowBridge
import io.github.kdroidfilter.nucleus.window.utils.windows.JniWindowsDecorationBridge
import io.github.kdroidfilter.nucleus.window.utils.windows.JniWindowsWindowUtil
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Toolkit

/**
 * Composition local that indicates whether the window is currently in
 * native (JNI-managed) fullscreen mode.
 */
internal val LocalNativeFullscreen = compositionLocalOf { false }

/**
 * Composition local providing a callback to exit native fullscreen.
 */
internal val LocalExitFullscreen = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Holder for the fullscreen title bar content.
 * [NativeWindowsTitleBar] stores its rendering lambda here when in fullscreen,
 * and [DecoratedWindow] renders it as an overlay outside the normal layout.
 *
 * [compositionLocalContext] captures the CompositionLocal context from the
 * original position in the tree (inside user content) so the overlay can
 * replay it and make user-provided CompositionLocals available.
 */
internal class FullscreenTitleBarHolder {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
    var titleBarHeight: Dp by mutableStateOf(0.dp)
    var compositionLocalContext: CompositionLocalContext? by mutableStateOf(null)
}

internal val LocalFullscreenTitleBarHolder = compositionLocalOf<FullscreenTitleBarHolder?> { null }

@Suppress("FunctionNaming", "LongParameterList", "CyclomaticComplexMethod", "LongMethod")
@Composable
fun DecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable AwtDecoratedWindowScope.() -> Unit,
) {
    val undecorated =
        when (Platform.Current) {
            Platform.Windows -> !JniWindowsDecorationBridge.isLoaded
            Platform.Linux -> true
            else -> false
        }

    val useNativeFullscreen =
        (Platform.Current == Platform.Windows && JniWindowsDecorationBridge.isLoaded) ||
            (Platform.Current == Platform.Linux && JniLinuxWindowBridge.isLoaded)
    val windowState =
        if (useNativeFullscreen) {
            remember(state) { NativeFullscreenWindowState(state) }
        } else {
            state
        }

    state.inflateToMinimumSize(minimumSize)

    // ── First-frame maximized fix ──────────────────────────────────────
    // When starting with WindowPlacement.Maximized, Compose's Window
    // creates the AWT window at state.size (e.g. 800×600) and renders
    // the first Skia frame at that size before the WM processes the
    // maximize. Override state.size with the screen work area so the
    // first frame matches the maximized dimensions.
    remember(state) {
        if (state.placement == WindowPlacement.Maximized) {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val gc = ge.defaultScreenDevice.defaultConfiguration
            val bounds = gc.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
            val scale = gc.defaultTransform.scaleX.toFloat()
            state.size =
                DpSize(
                    ((bounds.width - insets.left - insets.right) / scale).dp,
                    ((bounds.height - insets.top - insets.bottom) / scale).dp,
                )
        }
    }

    Window(
        onCloseRequest,
        windowState,
        visible,
        title,
        icon,
        undecorated,
        transparent = false,
        resizable,
        enabled,
        focusable,
        alwaysOnTop,
        onPreviewKeyEvent,
        onKeyEvent,
    ) {
        InstallMinimumSizeAfterCentering(minimumSize)

        if (useNativeFullscreen) {
            NativeFullscreenEffect(state, windowState)
            if (Platform.Current == Platform.Windows) {
                NativeFullscreenSyncEffect(state, windowState)
            }
        }

        val isNativeFullscreen = useNativeFullscreen && state.placement == WindowPlacement.Fullscreen
        val exitFullscreen: (() -> Unit)? =
            if (isNativeFullscreen) {
                {
                    val target =
                        (windowState as? NativeFullscreenWindowState)
                            ?.placementBeforeFullscreen ?: WindowPlacement.Floating
                    state.placement = target
                }
            } else {
                null
            }

        val titleBarHolder = remember { FullscreenTitleBarHolder() }

        // Clear holder content when leaving fullscreen.
        // On macOS, fullscreen is managed by AppKit (toggleFullScreen:), not by
        // our JNI mechanism. Compose's WindowState.placement reflects the
        // NSWindowStyleMaskFullScreen style mask, making it a reliable proxy
        // for macOS native fullscreen state.
        val isMacOSFullscreen =
            Platform.Current == Platform.MacOS && state.placement == WindowPlacement.Fullscreen
        if (!isNativeFullscreen && !isMacOSFullscreen) {
            titleBarHolder.content = null
        }

        var fullscreenBarVisible by remember { mutableStateOf(false) }
        val density = LocalDensity.current

        LaunchedEffect(isNativeFullscreen) {
            if (!isNativeFullscreen) fullscreenBarVisible = false
        }

        Box(
            modifier =
                if (isNativeFullscreen) {
                    Modifier.pointerInput(titleBarHolder.titleBarHeight) {
                        val titleBarHeightPx = with(density) { titleBarHolder.titleBarHeight.toPx() }
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val y =
                                    event.changes
                                        .firstOrNull()
                                        ?.position
                                        ?.y ?: continue
                                fullscreenBarVisible = y < titleBarHeightPx
                            }
                        }
                    }
                } else {
                    Modifier
                },
        ) {
            CompositionLocalProvider(
                LocalNativeFullscreen provides isNativeFullscreen,
                LocalExitFullscreen provides exitFullscreen,
                LocalFullscreenTitleBarHolder provides titleBarHolder,
            ) {
                DecoratedWindowBody(
                    title = title,
                    icon = icon,
                    undecorated = undecorated,
                    onCloseRequest = onCloseRequest,
                    content = content,
                )

                FullscreenTitleBarRenderers(
                    titleBarHolder = titleBarHolder,
                    isNativeFullscreen = isNativeFullscreen,
                    fullscreenBarVisible = fullscreenBarVisible,
                    title = title,
                    icon = icon,
                )
            }
        }
    }
}

/**
 * Renders the fullscreen title bar overlay(s), wrapping with the captured
 * [CompositionLocalContext] so user-provided CompositionLocals remain available.
 */
@Suppress("FunctionNaming")
@Composable
private fun BoxScope.FullscreenTitleBarRenderers(
    titleBarHolder: FullscreenTitleBarHolder,
    isNativeFullscreen: Boolean,
    fullscreenBarVisible: Boolean,
    title: String,
    icon: Painter?,
) {
    val ctx = titleBarHolder.compositionLocalContext
    val wrapper: @Composable (@Composable () -> Unit) -> Unit =
        if (ctx != null) {
            { content -> CompositionLocalProvider(ctx) { content() } }
        } else {
            { content -> content() }
        }

    val titleBarInfo = remember { TitleBarInfo(title, icon) }
    LaunchedEffect(title) { titleBarInfo.title = title }
    LaunchedEffect(icon) { titleBarInfo.icon = icon }

    if (isNativeFullscreen) {
        wrapper {
            CompositionLocalProvider(LocalTitleBarInfo provides titleBarInfo) {
                FullscreenTitleBarOverlay(
                    holder = titleBarHolder,
                    visible = fullscreenBarVisible,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }

    // macOS: always-visible overlay managed by MacOSTitleBar
    // (newFullscreenControls sets holder.content during macOS fullscreen)
    if (!isNativeFullscreen && titleBarHolder.content != null) {
        wrapper {
            CompositionLocalProvider(LocalTitleBarInfo provides titleBarInfo) {
                Box(modifier = Modifier.align(Alignment.TopCenter)) {
                    titleBarHolder.content?.invoke()
                }
            }
        }
    }
}

/**
 * Renders the fullscreen title bar as a sliding overlay.
 * Hidden above the top edge by default; slides down when [visible] is true.
 *
 * Visibility is controlled by the parent via [PointerEventPass.Initial] tracking
 * on the root Box, which receives all pointer events without blocking content clicks.
 */
@Suppress("FunctionNaming")
@Composable
private fun FullscreenTitleBarOverlay(
    holder: FullscreenTitleBarHolder,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val titleBarContent = holder.content ?: return
    val titleBarHeight = holder.titleBarHeight

    val offsetY by animateDpAsState(
        targetValue = if (visible) 0.dp else -titleBarHeight,
        animationSpec = tween(durationMillis = 200),
    )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .offset(y = offsetY),
    ) {
        titleBarContent()
    }
}

/**
 * Watches [state].placement and enters/exits native fullscreen accordingly.
 * A local [isNativeFullscreen] flag guards against redundant JNI calls if
 * [snapshotFlow] emits the same placement multiple times in quick succession.
 *
 * Works on both Windows (Win32 fullscreen) and Linux (_NET_WM_STATE_FULLSCREEN).
 */
@Composable
private fun FrameWindowScope.NativeFullscreenEffect(
    state: WindowState,
    windowState: WindowState,
) {
    LaunchedEffect(state, window) {
        var isNativeFullscreen = false
        // Track the last non-Fullscreen placement so we can restore it correctly
        // on exit — e.g. Maximized instead of always falling back to Floating.
        var lastNonFullscreenPlacement =
            state.placement.takeIf { it != WindowPlacement.Fullscreen }
                ?: WindowPlacement.Floating
        snapshotFlow { state.placement }.collect { placement ->
            if (placement != WindowPlacement.Fullscreen) {
                lastNonFullscreenPlacement = placement
            }
            if (placement == WindowPlacement.Fullscreen && !isNativeFullscreen) {
                // Persist the pre-fullscreen placement so the exit callback
                // restores the correct state (Maximized, Floating, etc.).
                (windowState as? NativeFullscreenWindowState)
                    ?.placementBeforeFullscreen = lastNonFullscreenPlacement
                when (Platform.Current) {
                    Platform.Windows -> {
                        val hwnd = JniWindowsWindowUtil.getHwnd(window)
                        if (hwnd != 0L) JniWindowsDecorationBridge.nativeSetFullscreen(hwnd, true)
                    }
                    Platform.Linux -> {
                        JniLinuxWindowBridge.nativeSetFullscreen(window, true)
                    }
                    else -> {}
                }
                isNativeFullscreen = true
            } else if (placement != WindowPlacement.Fullscreen && isNativeFullscreen) {
                when (Platform.Current) {
                    Platform.Windows -> {
                        val hwnd = JniWindowsWindowUtil.getHwnd(window)
                        if (hwnd != 0L) JniWindowsDecorationBridge.nativeSetFullscreen(hwnd, false)
                        // Safety net: ensure AWT's extendedState matches the
                        // restored placement. SetWindowPlacement sends the proper
                        // WM_SIZE events, but AWT may still miss the maximize
                        // notification if it processed an intermediate resize
                        // during style restoration. Explicitly setting extendedState
                        // guarantees DecoratedWindowState.isMaximized stays in sync.
                        if (lastNonFullscreenPlacement == WindowPlacement.Maximized) {
                            window.extendedState = Frame.MAXIMIZED_BOTH
                        } else {
                            window.extendedState =
                                window.extendedState and Frame.MAXIMIZED_BOTH.inv()
                        }
                    }
                    Platform.Linux -> {
                        JniLinuxWindowBridge.nativeSetFullscreen(window, false)
                    }
                    else -> {}
                }
                // The caller may have written any non-Fullscreen value as a
                // trigger to exit (e.g. Floating regardless of previous state).
                // Override the delegate with the actual pre-fullscreen placement
                // so Compose's Window composable syncs to the correct state and
                // does not fight the native SetWindowPlacement restoration.
                if (placement != lastNonFullscreenPlacement) {
                    state.placement = lastNonFullscreenPlacement
                }
                isNativeFullscreen = false
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
//  NativeFullscreenSyncEffect (Windows only)
// ──────────────────────────────────────────────────────────────────────

/**
 * Attaches a [java.awt.event.ComponentListener] that detects when the native
 * window is resized while [state].placement is [WindowPlacement.Fullscreen].
 *
 * This covers the case where the WM_SIZE safety net in the native WndProc
 * clears [isFullscreen] (e.g. because AWT called ShowWindow directly, bypassing
 * WM_SYSCOMMAND blocking). When a resize is detected and [nativeIsFullscreen]
 * returns false, Kotlin's placement is restored to the pre-fullscreen value so
 * the two layers stay in sync.
 *
 * Setting [state].placement from the AWT event thread is safe because
 * Compose's [mutableStateOf] backing is thread-safe.
 */
@Composable
private fun FrameWindowScope.NativeFullscreenSyncEffect(
    state: WindowState,
    windowState: WindowState,
) {
    DisposableEffect(window) {
        val listener =
            object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    if (state.placement != WindowPlacement.Fullscreen) return
                    val hwnd = JniWindowsWindowUtil.getHwnd(window)
                    if (hwnd != 0L && !JniWindowsDecorationBridge.nativeIsFullscreen(hwnd)) {
                        val previous =
                            (windowState as? NativeFullscreenWindowState)
                                ?.placementBeforeFullscreen ?: WindowPlacement.Floating
                        state.placement = previous
                    }
                }
            }
        window.addComponentListener(listener)
        onDispose { window.removeComponentListener(listener) }
    }
}

// ──────────────────────────────────────────────────────────────────────
//  NativeFullscreenWindowState wrapper
// ──────────────────────────────────────────────────────────────────────

/**
 * Wraps a [WindowState] to prevent Compose from seeing [WindowPlacement.Fullscreen].
 *
 * When the delegate's placement is Fullscreen, this wrapper:
 * - **getter**: returns the placement that was active before fullscreen, so Compose's
 *   Window never triggers its own (broken) exclusive fullscreen mode.
 * - **setter**: blocks all writes from Compose's internal sync (which would overwrite
 *   the Fullscreen value with Floating/Maximized and trigger an immediate exit).
 *
 * User code writes directly to the delegate (the original [WindowState]), not through
 * this wrapper. Only Compose's [Window] composable writes through the wrapper.
 */
internal class NativeFullscreenWindowState(
    private val delegate: WindowState,
) : WindowState {
    internal var placementBeforeFullscreen: WindowPlacement =
        delegate.placement.takeIf { it != WindowPlacement.Fullscreen }
            ?: WindowPlacement.Floating

    /** True when the delegate holds [WindowPlacement.Fullscreen]. */
    private val isInNativeFullscreen: Boolean
        get() = delegate.placement == WindowPlacement.Fullscreen

    override var placement: WindowPlacement
        get() {
            val p = delegate.placement
            return if (p == WindowPlacement.Fullscreen) placementBeforeFullscreen else p
        }
        set(value) {
            if (isInNativeFullscreen) return

            if (delegate.placement != WindowPlacement.Fullscreen && value == WindowPlacement.Fullscreen) {
                placementBeforeFullscreen = delegate.placement
            }
            delegate.placement = value
        }

    override var isMinimized: Boolean
        get() = delegate.isMinimized
        set(value) {
            if (isInNativeFullscreen) return
            delegate.isMinimized = value
        }

    override var position: WindowPosition
        get() = delegate.position
        set(value) {
            if (isInNativeFullscreen) return
            delegate.position = value
        }

    override var size: DpSize
        get() = delegate.size
        set(value) {
            if (isInNativeFullscreen) return
            delegate.size = value
        }
}
