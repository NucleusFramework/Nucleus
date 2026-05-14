package io.github.kdroidfilter.nucleus.window

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.macos.JniMacTitleBarBridge
import io.github.kdroidfilter.nucleus.window.utils.macos.JniMacWindowUtil
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

private const val MENU_BAR_ANIMATION_MS = 200

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun DecoratedWindowScope.MacOSTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    val useNewFullscreenControls = modifier.hasNewFullscreenControls()
    val useLargeCornerRadius = modifier.hasMacOSLargeCornerRadius()

    // Notify native side about the newFullscreenControls preference
    DisposableEffect(window, useNewFullscreenControls) {
        if (useNewFullscreenControls) {
            val ptr = JniMacWindowUtil.getWindowPtr(window)
            if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
                JniMacTitleBarBridge.nativeSetNewFullscreenControls(ptr, true)
            }
        }
        onDispose {
            if (useNewFullscreenControls) {
                val ptr = JniMacWindowUtil.getWindowPtr(window)
                if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
                    JniMacTitleBarBridge.nativeSetNewFullscreenControls(ptr, false)
                }
            }
        }
    }

    // Install/remove invisible NSToolbar for 26pt corner radius
    DisposableEffect(window, useLargeCornerRadius) {
        val ptr = JniMacWindowUtil.getWindowPtr(window)
        if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
            JniMacTitleBarBridge.nativeSetLargeCornerRadius(ptr, useLargeCornerRadius)
        }
        onDispose {
            if (useLargeCornerRadius) {
                val ptr2 = JniMacWindowUtil.getWindowPtr(window)
                if (ptr2 != 0L && JniMacTitleBarBridge.isLoaded) {
                    JniMacTitleBarBridge.nativeSetLargeCornerRadius(ptr2, false)
                }
            }
        }
    }

    DisposableEffect(window) {
        onDispose {
            val ptr = JniMacWindowUtil.getWindowPtr(window)
            if (ptr != 0L) {
                JniMacTitleBarBridge.nativeResetTitleBar(ptr)
                JniMacTitleBarBridge.removeMenuBarOffsetFlow(ptr)
            }
        }
    }

    // Sync RTL state with native side so traffic-light buttons move to the
    // correct side. Uses the control buttons direction (decoupled from content).
    val controlDir = controlButtonsDirection.resolve()
    val controlIsRtl = controlDir == LayoutDirection.Rtl
    val controlsSide = if (controlIsRtl) WindowControlsSide.Start else WindowControlsSide.End
    LaunchedEffect(window, controlIsRtl) {
        val ptr = JniMacWindowUtil.getWindowPtr(window)
        if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
            JniMacTitleBarBridge.nativeSetRTL(ptr, controlIsRtl)
        }
    }

    val background by style.colors.backgroundFor(state)

    // ── Menu bar offset for fullscreen ──
    // In fullscreen on non-notch screens, the system menu bar auto-hides.
    // When it appears (mouse at top), it pushes the title bar down — and
    // since the title bar is in the normal layout, the content below it
    // is pushed down too (like Safari). On notch screens the menu bar
    // lives in the notch area so the offset stays at 0.
    val isFullscreenWithNewControls = state.isFullscreen && useNewFullscreenControls

    // Install/remove the native menu bar monitor during fullscreen.
    // The ptr is evaluated inside the effect so it picks up the AWT peer
    // even if it wasn't available at initial composition.
    DisposableEffect(window, isFullscreenWithNewControls) {
        val ptr = JniMacWindowUtil.getWindowPtr(window)
        if (isFullscreenWithNewControls && ptr != 0L && JniMacTitleBarBridge.isLoaded) {
            JniMacTitleBarBridge.nativeInstallMenuBarMonitor(ptr)
        }
        onDispose {
            if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
                JniMacTitleBarBridge.nativeRemoveMenuBarMonitor(ptr)
            }
        }
    }

    // Collect the menu bar offset. The ptr must be fresh here too.
    val currentPtr = JniMacWindowUtil.getWindowPtr(window)
    val menuBarOffsetPt by remember(currentPtr) {
        JniMacTitleBarBridge.menuBarOffsetFlow(currentPtr)
    }.collectAsState()

    val menuBarOffset by animateDpAsState(
        targetValue = if (isFullscreenWithNewControls) menuBarOffsetPt.dp else 0.dp,
        animationSpec = tween(durationMillis = MENU_BAR_ANIMATION_MS),
    )

    // Push animated offset to native so traffic-light buttons follow.
    LaunchedEffect(menuBarOffset) {
        val ptr = JniMacWindowUtil.getWindowPtr(window)
        if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
            JniMacTitleBarBridge.nativeSetMenuBarOffset(ptr, menuBarOffset.value)
        }
    }

    // ── Title bar (always in layout, never overlay) ──
    val viewConfig = LocalViewConfiguration.current
    var lastPress = 0L

    CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
        TitleBarImpl(
            modifier =
                Modifier
                    .offset(y = menuBarOffset)
                    .zIndex(if (menuBarOffset > 0.dp) 1f else 0f)
                    .then(modifier)
                    .titleBarHitTestHandler(window)
                    .onPointerEvent(PointerEventType.Press, PointerEventPass.Final) {
                        if (
                            this.currentEvent.button == PointerButton.Primary &&
                            this.currentEvent.changes.any { !it.isConsumed }
                        ) {
                            val now = System.currentTimeMillis()
                            if (
                                now - lastPress in
                                viewConfig.doubleTapMinTimeMillis..viewConfig.doubleTapTimeoutMillis
                            ) {
                                val p = JniMacWindowUtil.getWindowPtr(window)
                                if (p != 0L && JniMacTitleBarBridge.isLoaded) {
                                    JniMacTitleBarBridge.nativePerformTitleBarDoubleClickAction(p)
                                }
                            }
                            lastPress = now
                        }
                    },
            gradientStartColor = gradientStartColor,
            style = style,
            controlButtonsDirection = controlDir,
            layoutPolicy = layoutPolicy,
            applyTitleBar = { height, titleBarState ->
                JniMacWindowUtil.applyWindowProperties(window)

                val p = JniMacWindowUtil.getWindowPtr(window)
                val padding =
                    if (titleBarState.isFullscreen) {
                        if (controlIsRtl) {
                            PaddingValues(end = 80.dp)
                        } else {
                            PaddingValues(start = 80.dp)
                        }
                    } else {
                        val buttonInset =
                            if (p != 0L && JniMacTitleBarBridge.isLoaded) {
                                JniMacTitleBarBridge.nativeApplyTitleBar(p, height.value)
                            } else {
                                @Suppress("MagicNumber")
                                val shrink = minOf(height.value / 28f, 1f)

                                @Suppress("MagicNumber")
                                val leftMargin = minOf(height.value / 2f, 20f)

                                @Suppress("MagicNumber")
                                2f * leftMargin + 2f * shrink * 20f
                            }
                        if (controlIsRtl) {
                            PaddingValues(end = buttonInset.dp)
                        } else {
                            PaddingValues(start = buttonInset.dp)
                        }
                    }
                padding
            },
            onPlace = {
                if (state.isFullscreen) {
                    val p = JniMacWindowUtil.getWindowPtr(window)
                    if (p != 0L && JniMacTitleBarBridge.isLoaded) {
                        JniMacTitleBarBridge.nativeUpdateFullScreenButtons(p)
                    }
                }
            },
            backgroundContent = {
                Spacer(modifier = Modifier.fillMaxSize())
                backgroundContent()
            },
            content = content,
        )
    }
}

/**
 * Mirrors JBR's `customTitleBarMouseEventHandler` / `forceHitTest` approach.
 * Runs on the parent modifier (Main pass, after children have processed events).
 *
 * - Unconsumed Press → marks a pending drag (button down on empty title bar area).
 * - Unconsumed Move while pending → initiates native window drag via JNI.
 * - Consumed Press → enters `inUserControl` (interactive child handles it).
 * - Release → resets state.
 *
 * The native NucleusDragView is a pure pass-through; all drag decisions live here.
 */
internal fun Modifier.titleBarHitTestHandler(window: java.awt.Window): Modifier =
    pointerInput(window) {
        val ctx = coroutineContext
        awaitPointerEventScope {
            var inUserControl = false
            var pendingDrag = false
            while (ctx.isActive) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                event.changes.forEach {
                    if (!it.isConsumed && !inUserControl) {
                        when (event.type) {
                            PointerEventType.Press -> pendingDrag = true
                            PointerEventType.Move ->
                                if (pendingDrag) {
                                    startWindowDrag(window)
                                    pendingDrag = false
                                }
                            PointerEventType.Release -> pendingDrag = false
                        }
                    } else {
                        if (event.type == PointerEventType.Press) {
                            inUserControl = true
                            pendingDrag = false
                        }
                        if (event.type == PointerEventType.Release) {
                            inUserControl = false
                        }
                    }
                }
            }
        }
    }

private fun startWindowDrag(window: java.awt.Window) {
    val ptr = JniMacWindowUtil.getWindowPtr(window)
    if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
        JniMacTitleBarBridge.nativeStartWindowDrag(ptr)
    }
}
