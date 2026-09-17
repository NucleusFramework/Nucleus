package dev.nucleusframework.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.ControlButtonsDirection
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.GenericTitleBarImpl
import dev.nucleusframework.window.TitleBarScope
import dev.nucleusframework.window.hasMacOSLargeCornerRadius
import dev.nucleusframework.window.hasNewFullscreenControls
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.styling.TitleBarStyle
import dev.nucleusframework.window.tao.LocalRequestedTitleBarHeight
import dev.nucleusframework.window.tao.LocalWindowClearColorLayers
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.deco.LocalFullscreenTitleBarHolder
import dev.nucleusframework.window.tao.deco.WindowControlsLinux
import dev.nucleusframework.window.tao.deco.WindowControlsWindows
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge
import dev.nucleusframework.window.utils.linux.rememberLinuxButtonLayout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

// KDE breeze gives the leading edge of its title bar a small padding so the
// edge-most window control button doesn't sit flush against the window border.
// Mirrors `decorated-window-core/TitleBarLinuxCommon.kt::kdePaddingForButtonLayout`.
private val LINUX_KDE_EDGE_PADDING: Dp = 4.dp
private val isLinuxKde: Boolean =
    Platform.Current == Platform.Linux &&
        LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE

private const val WINDOW_RECT_COMPONENT_COUNT = 4
private const val SCREEN_POINT_COMPONENT_COUNT = 2

/**
 * Platform-aware title bar for the Tao-backed [DecoratedWindow].
 *
 * Signature mirrors the legacy AWT backend so an app
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
 *   each side), matching the legacy AWT backend's JBR-driven inset path.
 * - KDE breeze 4 dp edge padding applied on the controls side.
 * - Linux + Windows control buttons are injected here (no native chrome).
 */
@Suppress("FunctionNaming")
@Composable
public fun DecoratedWindowScope.TitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    BasicTitleBar(
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlButtonsDirection,
        layoutPolicy = TitleBarLayoutPolicy.Default,
        backgroundContent = backgroundContent,
        content = content,
    )
}

/**
 * [TitleBar] with the measure policy left open, for chrome that needs a
 * different arrangement than the platform default — a strip that fills the
 * space between the platform controls, for instance
 * ([TitleBarLayoutPolicy.FillCenter]).
 *
 * @param nativeWindowDrag whether pressing the bar starts the platform's own
 *   interactive move. On by default, which is what gives the window the OS
 *   snapping and tiling. Turn it off for a window that moves *itself* during
 *   the gesture: the platform move is a compositor grab that swallows every
 *   pointer event up to and including the release, so a window moved that way
 *   cannot decide anything when it lands. The caller then supplies its own
 *   drag through [modifier], which covers the whole bar rather than only the
 *   part its content happens to occupy.
 */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
public fun DecoratedWindowScope.BasicTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    nativeWindowDrag: Boolean = true,
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

    // Modifier-driven flags. `macOSLargeCornerRadius` re-runs the NSToolbar
    // install at composition time so the modifier-driven path matches the
    // AWT backends (the `MacOSStyle` parameter at window creation is the
    // imperative equivalent — both are honoured).
    val newFullscreenControls = modifier.hasNewFullscreenControls()
    val macOSLargeCornerRadius = modifier.hasMacOSLargeCornerRadius()
    val isMacOS = Platform.Current == Platform.MacOS
    if (isMacOS && macOSLargeCornerRadius) {
        LaunchedEffect(taoWindow) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
            if (nsView != 0L && NativeMetalBridge.isLoaded) {
                NativeMetalBridge.nativeApplyLargeCornerRadius(nsView, true)
            }
        }
    }

    // ── newFullscreenControls (macOS) ─────────────────────────────────────
    // Mirrors the legacy AWT backend's macOS title bar. In native fullscreen
    // on a non-notch screen the system menu bar auto-hides; when it slides
    // back in we offset the title bar (and the AppKit traffic-light
    // replacements) by the menu bar height so they read like Safari.
    val isFullscreenWithNewControls =
        isMacOS && newFullscreenControls && currentState.isFullscreen

    // Push the flag to native so the FS observer installs the menu bar
    // monitor automatically across fullscreen transitions (mirrors JNI's
    // `nativeSetNewFullscreenControls`).
    DisposableEffect(taoWindow, newFullscreenControls) {
        if (isMacOS && newFullscreenControls && NativeMetalBridge.isLoaded) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
            if (nsView != 0L) {
                NativeMetalBridge.nativeSetNewFullscreenControls(nsView, true)
            }
        }
        onDispose {
            if (isMacOS && newFullscreenControls && NativeMetalBridge.isLoaded) {
                val ptr = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
                if (ptr != 0L) {
                    NativeMetalBridge.nativeSetNewFullscreenControls(ptr, false)
                }
            }
        }
    }

    // Install/remove the native menu bar monitor while we're fullscreen +
    // opted-in. Belt-and-braces with the FS observer's own install on
    // didEnterFS — composition can outlive a transition (or vice-versa).
    DisposableEffect(taoWindow, isFullscreenWithNewControls) {
        if (isFullscreenWithNewControls && NativeMetalBridge.isLoaded) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
            if (nsView != 0L) {
                NativeMetalBridge.nativeInstallMenuBarMonitor(nsView)
            }
        }
        onDispose {
            if (isMacOS && NativeMetalBridge.isLoaded) {
                val ptr = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
                if (ptr != 0L) {
                    NativeMetalBridge.nativeRemoveMenuBarMonitor(ptr)
                }
            }
        }
    }

    // Drop the per-window StateFlow when the title bar leaves the tree so
    // the ConcurrentHashMap doesn't accumulate stale entries.
    DisposableEffect(taoWindow) {
        onDispose {
            if (isMacOS) {
                val ptr = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
                if (ptr != 0L) NativeMetalBridge.removeMenuBarOffsetFlow(ptr)
            }
        }
    }

    val currentNsView = if (isMacOS) NativeTaoBridge.nativeNsViewHandle(taoWindow.handle) else 0L
    val menuBarOffsetPt by remember(currentNsView) {
        NativeMetalBridge.menuBarOffsetFlow(currentNsView)
    }.collectAsState()

    // The native side streams the live menu-bar reveal fraction (Carbon
    // kEventClassMenu — see NucleusTaoMetal.m), so the offset is applied
    // as-is: the system animation drives the title bar directly, like
    // Chrome's fullscreen toolbar. Animating here again would trail AppKit's
    // own slide by the tween duration.
    val menuBarOffset = if (isFullscreenWithNewControls) menuBarOffsetPt.dp else 0.dp

    // Push the resolved title-bar background into the Skia clear color,
    // re-applied every frame by every Tao host (macOS / Windows / Linux), so
    // any Compose region without an explicit background matches the chrome
    // color rather than flashing a hardcoded white or — on Linux — showing
    // the desktop through the transparent CSD clear. This is the Tao
    // equivalent of the AWT NSWindow background that jbr/jni get for free from
    // the user's theme (see `DecoratedWindowBody`'s `Modifier.background`).
    // On Linux the host still carves the rounded corners back to transparent
    // after rendering.
    // Keyed content stack: co-composed WindowBackground survives when this
    // TitleBar is removed (clearContent only drops this writer).
    // Fully transparent windows (#416): WindowClearColorLayers coerces opaque
    // chrome colours to alpha-0 for the clear only — the TitleBar still paints
    // its own bar; empty client regions stay see-through.
    val titleBarBackground by style.colors.backgroundFor(currentState)
    val clearColorLayers = LocalWindowClearColorLayers.current
    val clearColorKey = remember { Any() }
    SideEffect {
        clearColorLayers?.setContent(clearColorKey, titleBarBackground.toArgb())
    }
    DisposableEffect(clearColorKey) {
        onDispose {
            clearColorLayers?.clearContent(clearColorKey)
        }
    }

    // Push the animated offset back to native so the AppKit traffic-light
    // replacements follow the Compose title bar pixel-for-pixel.
    LaunchedEffect(menuBarOffset, isMacOS) {
        if (isMacOS && NativeMetalBridge.isLoaded) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
            if (nsView != 0L) {
                NativeMetalBridge.nativeSetMenuBarOffset(nsView, menuBarOffset.value)
            }
        }
    }

    val linuxLayout = if (Platform.Current == Platform.Linux) rememberLinuxButtonLayout() else null
    val controlDir = controlButtonsDirection.resolve()
    val controlIsRtl = controlDir == LayoutDirection.Rtl

    // Match the AWT/JNI backends: [controlDir] drives both the control side
    // and the internal button order, independently of the title bar content.
    val controlsPlacementDir = controlDir

    // macOS: flip the AppKit traffic-lights to the right edge when RTL is
    // active. Mirrors the legacy AWT backend's `nativeSetRTL` call path.
    if (Platform.Current == Platform.MacOS) {
        LaunchedEffect(taoWindow, controlIsRtl) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
            if (nsView != 0L && NativeMetalBridge.isLoaded) {
                NativeMetalBridge.nativeSetButtonLayoutRtl(nsView, controlIsRtl)
            }
        }
    }

    val rootModifier =
        Modifier
            // macOS only — slide the title bar down when the system menu bar
            // appears in fullscreen. Pure visual offset (no layout reflow):
            // the bar overlaps user content the same way Safari does.
            .let {
                if (isMacOS) it.offset(y = menuBarOffset).zIndex(if (menuBarOffset > 0.dp) 1f else 0f) else it
            }.then(modifier)
            // Bind drag to [taoWindow] explicitly (not only LocalTaoWindow) so
            // secondary windows stay movable when parent CompositionLocals are
            // bridged into this scene and would otherwise clobber LocalTaoWindow.
            //
            // Opted out of by a window that moves itself, which is the only way
            // a move can decide anything on release: `windowDragArea` hands the
            // gesture to the compositor, and the compositor then swallows every
            // pointer event including the release. See [nativeWindowDrag].
            .let { if (nativeWindowDrag) it.windowDragArea(window = taoWindow) else it }

    val overlayHolder = LocalFullscreenTitleBarHolder.current
    val useOverlay =
        newFullscreenControls &&
            currentState.isFullscreen &&
            (Platform.Current == Platform.Windows || Platform.Current == Platform.Linux) &&
            overlayHolder != null

    val titleBarRendering: @Composable () -> Unit = {
        GenericTitleBarImpl(
            state = currentState,
            modifier = rootModifier,
            gradientStartColor = gradientStartColor,
            style = style,
            controlButtonsDirection = controlDir,
            controlButtonsPlacementDirection = controlsPlacementDir,
            layoutPolicy = layoutPolicy,
            applyTitleBar = { measuredHeight, titleBarState ->
                // In overlay mode the bar lives outside the user content tree;
                // the inline slot is collapsed so heightHolder must stay at 0
                // (drives both the Compose top inset and the deco caption zone).
                if (!useOverlay) {
                    heightHolder.value = measuredHeight.value
                }
                titleBarPadding(
                    measuredHeight = measuredHeight,
                    isFullscreen = titleBarState.isFullscreen,
                    controlIsRtl = controlDir == LayoutDirection.Rtl,
                    linuxControlsOnRight = linuxLayout?.controlsOnRight,
                )
            },
            onPlace = {
                // macOS fullscreen: keep the AppKit replacement traffic-lights
                // pinned to whatever Y the Compose title bar is currently at.
                // Mirrors the legacy AWT backend's `nativeUpdateFullScreenButtons`.
                if (isMacOS && currentState.isFullscreen && NativeMetalBridge.isLoaded) {
                    val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
                    if (nsView != 0L) {
                        NativeMetalBridge.nativeUpdateFullScreenButtons(nsView)
                    }
                }
            },
            backgroundContent = backgroundContent,
            content = { titleBarState ->
                // Window controls are declared BEFORE user content so core's
                // [TitleBarMeasurePolicy] places them at the extreme edge first
                // (first-declared End item = rightmost in LTR; first-declared
                // Start item = leftmost). Mirrors the legacy AWT backend's
                // TitleBar.{Linux,Windows}.kt where WindowControlArea is invoked
                // ahead of `content()`.
                when (Platform.Current) {
                    Platform.Linux ->
                        if (linuxLayout != null) {
                            WindowControlsLinux(
                                win = taoWindow,
                                state = titleBarState,
                                isResizable = taoWindow.isResizable,
                                style = style,
                                layout = linuxLayout,
                                isFullscreen = titleBarState.isFullscreen,
                                onExitFullscreen = { taoWindow.setFullscreen(false) },
                            )
                        }
                    Platform.Windows ->
                        WindowControlsWindows(
                            win = taoWindow,
                            state = titleBarState,
                            style = style,
                            modifier = Modifier.align(Alignment.End),
                            isFullscreen = titleBarState.isFullscreen,
                            onExitFullscreen = { taoWindow.setFullscreen(false) },
                        )
                    else -> Unit // macOS uses native AppKit traffic-lights
                }

                content(titleBarState)
            },
        )
    }

    // newFullscreenControls: when fullscreen on Windows, hand the title-bar
    // rendering off to the [FullscreenOverlayHost] which slides it in/out
    // based on pointer Y. The inline slot collapses to nothing so the user
    // content fills the screen, and the deco's caption zone is zeroed so the
    // WndProc returns HTCLIENT everywhere (the overlay handles its own input).
    //
    // The handoff runs during COMPOSITION, not in a SideEffect: these state
    // writes invalidate FullscreenOverlayHost's scope within the SAME frame,
    // so the first fullscreen composition already shows a collapsed inline
    // slot AND the armed overlay. Publishing from a SideEffect landed one
    // frame late — a composited frame with no title bar at all mid-toggle
    // (the issue-413 layout corruption); the LaunchedEffect-based clear on
    // exit was likewise one frame late (a frame with both bars).
    val latestTitleBarRendering by rememberUpdatedState(titleBarRendering)
    val overlayContent = remember { @Composable { latestTitleBarRendering() } }
    if (useOverlay && overlayHolder != null) {
        val ctx = currentCompositionLocalContext
        heightHolder.value = 0f
        overlayHolder.titleBarHeight = style.metrics.height
        if (overlayHolder.compositionLocalContext !== ctx) {
            overlayHolder.compositionLocalContext = ctx
        }
        // Stable lambda: assigning the same instance on every recomposition
        // keeps the holder's state from invalidating the overlay each frame.
        if (overlayHolder.content !== overlayContent) {
            overlayHolder.content = overlayContent
        }
    } else {
        titleBarRendering()
        // Same-frame clear on the recomposition that turns the overlay off.
        if (overlayHolder != null && overlayHolder.content === overlayContent) {
            overlayHolder.content = null
        }
    }

    // Push the resolved caption height to the deco WndProc on every overlay
    // toggle. host.syncTitleBarHeight() only fires on resize/scale changes,
    // which can race the recomposition that flips `useOverlay`.
    if (Platform.Current == Platform.Windows) {
        val styleHeightPx = with(LocalDensity.current) { style.metrics.height.roundToPx() }
        LaunchedEffect(taoWindow, useOverlay) {
            if (!NativeTaoWindowsDecoBridge.isLoaded) return@LaunchedEffect
            val hwnd = NativeTaoBridge.nativeHwndHandle(taoWindow.handle)
            if (hwnd == 0L) return@LaunchedEffect
            val px = if (useOverlay) 0 else styleHeightPx
            NativeTaoWindowsDecoBridge.nativeSetTitleBarHeight(hwnd, px)
        }
    }
}

/**
 * Platform-specific reservation insets returned to [GenericTitleBarImpl]'s
 * `applyTitleBar` callback. Mirrors the legacy AWT backend's `MacOSTitleBar`
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
            val inset =
                if (isFullscreen) {
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

// Mirrors the legacy AWT backend's `titleBarHitTestHandler`.
// Press → mark pendingDrag (no consumption). Move while pending → start the
// native window drag. Consumed Press → enter `inUserControl` and skip drag.
//
// Pointer pass is Final (leaf → root), not Main: Main walks root → leaf, so
// this handler would arm pendingDrag *before* a child could claim the press
// (`clickable`, `noWindowDrag`, etc.). Final runs after Main, so consumption
// by descendants is already visible — which is what makes `noWindowDrag` and
// interactive chrome children actually opt out of the window move.
//
// On macOS this is the *only* path that drags the window — AppKit's native
// title-bar drag is disabled by `[NSWindow setMovable:NO]` in
// `nativeConfigureChrome`, so clicks in the title bar reach Compose
// undisturbed. The native side defers `performWindowDragWithEvent:` via
// `dispatch_async`, mirroring JNI exactly.
//
// Deliberately does NOT opt into `sharePointerInputWithSiblings`: that flag is
// read per layout node (`InnerNodeCoordinator` consults the immediate child's
// chain), so it would not help the `TitleBarPlacement.Overlay` case it looks
// like it addresses — but it WOULD let every overlapping sibling below a
// `windowDragArea` (the macOS fullscreen bar, which is offset over the content
// in the same Column; any app stacking chrome over content in a Box) receive
// presses aimed at the opaque chrome. Pass-through is opted into explicitly,
// at the scaffold level — see `Modifier.shareHitTestWithSiblings`.
internal fun Modifier.titleBarHitTestHandler(window: TaoWindow): Modifier =
    pointerInput(window) { titleBarDragPointerLoop(window) }

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("CyclomaticComplexMethod")
private suspend fun PointerInputScope.titleBarDragPointerLoop(window: TaoWindow) {
    val ctx = currentCoroutineContext()
    awaitPointerEventScope {
        var inUserControl = false
        var pendingDrag = false
        // Windows touch drag state lives in [TaoWindow] and is driven by
        // raw Tao touch events (see `TaoComposeSceneHostWindows.onTouchInput`
        // → `window.updateWindowsTitleBarTouchDrag(…)`), so the drag keeps
        // running even if Compose's pointer pipeline routing is disrupted
        // by the layout-size change of a `maximized → floating` restore.
        while (ctx.isActive) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            event.changes.forEach {
                val isTouch = it.type == PointerType.Touch
                if (!it.isConsumed && !inUserControl) {
                    when (event.type) {
                        PointerEventType.Press -> {
                            val touchOnWindows =
                                isTouch &&
                                    Platform.Current == Platform.Windows &&
                                    NativeTaoWindowsDecoBridge.isLoaded
                            // Only the primary button (or touch) may drag the window.
                            // A secondary/middle press must not arm the drag: on Linux
                            // `dragWindow()` starts an interactive compositor move grab
                            // that swallows every subsequent pointer event.
                            val isPrimaryOrTouch =
                                event.button == PointerButton.Primary || isTouch
                            pendingDrag = isPrimaryOrTouch && !touchOnWindows
                            if (touchOnWindows) {
                                // Fallback touch drag (no Aero Snap): only reached if the
                                // native WndProc did NOT capture this title-bar touch (it
                                // normally consumes the whole interaction from WM_POINTERDOWN
                                // and hands it to the OS move loop). Subsequent samples run
                                // via `TaoComposeSceneHostWindows.onTouchInput` →
                                // `window.updateWindowsTitleBarTouchDrag(...)`, applying
                                // `SetWindowPos` directly so the window still follows the
                                // finger even when the OS-driven path is unavailable.
                                val hwnd =
                                    dev.nucleusframework.window.tao.ffi.NativeTaoBridge
                                        .nativeHwndHandle(window.handle)
                                val rect =
                                    if (hwnd != 0L) {
                                        NativeTaoWindowsDecoBridge
                                            .nativeGetWindowRect(hwnd)
                                    } else {
                                        null
                                    }
                                val screen =
                                    if (hwnd != 0L) {
                                        NativeTaoWindowsDecoBridge
                                            .nativeClientToScreen(
                                                hwnd,
                                                it.position.x.toInt(),
                                                it.position.y.toInt(),
                                            )
                                    } else {
                                        null
                                    }
                                if (
                                    rect != null &&
                                    rect.size == WINDOW_RECT_COMPONENT_COUNT &&
                                    screen != null &&
                                    screen.size == SCREEN_POINT_COMPONENT_COUNT
                                ) {
                                    window.beginWindowsTitleBarTouchDrag(
                                        touchId = it.id.value,
                                        hwnd = hwnd,
                                        startScreenX = screen[0],
                                        startScreenY = screen[1],
                                        startOuterX = rect[0],
                                        startOuterY = rect[1],
                                        maximized =
                                            NativeTaoWindowsDecoBridge
                                                .nativeIsMaximized(hwnd),
                                    )
                                }
                            }
                        }
                        PointerEventType.Move ->
                            if (pendingDrag) {
                                window.dragWindow()
                                pendingDrag = false
                            }
                        PointerEventType.Release -> {
                            pendingDrag = false
                        }
                    }
                } else {
                    // Someone else claimed this sample — a child gesture
                    // detector, or (under an Overlay bar sharing its hit test)
                    // a scrollable sibling below. Disarm: a consumer that stops
                    // consuming mid-gesture — a list reaching its scroll limit —
                    // would otherwise hand the still-pending drag a later
                    // unconsumed Move and start a compositor move grab in the
                    // middle of the scroll.
                    pendingDrag = false
                    if (event.type == PointerEventType.Press) {
                        inUserControl = true
                    }
                    if (event.type == PointerEventType.Release) {
                        inUserControl = false
                    }
                }
            }
        }
    }
}
