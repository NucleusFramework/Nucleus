@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.styling.LocalDecoratedWindowStyle
import kotlin.math.roundToInt

/**
 * Composable variant of [openDecoratedWindow]. API mirrors
 * `decorated-window-jni`'s `DecoratedWindow`.
 *
 * Reactive parameters (`title`, `alwaysOnTop`, `visible`, `focusable`,
 * `minimumSize`, `icon`, every field of [state]) push to the underlying
 * [TaoWindow] via `LaunchedEffect`. Callback parameters (`onCloseRequest`,
 * `onPreviewKeyEvent`, `onKeyEvent`) are reactive without recreating the
 * window.
 *
 * State sync is bidirectional: when the user drags or resizes the window
 * natively, [state] is updated. The `applied` snapshot guards against
 * feedback loops so we don't write back values we ourselves originated.
 *
 * Limitations vs. `decorated-window-jni`:
 *  - `enabled` only applies at construction (no live disabling yet).
 *  - User `content` lambda captures latest via `rememberUpdatedState`; state
 *    declared in the parent application scope and read inside `content`
 *    propagates via snapshot but does not share a CompositionContext.
 */
@Suppress("LongParameterList", "FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun ApplicationScope.DecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    title: String = "",
    icon: Painter? = null,
    minimumSize: DpSize? = null,
    visible: Boolean = true,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    isDialog: Boolean = false,
    /** Fully borderless window (no traffic lights on macOS) — for overlays/ghosts. */
    undecorated: Boolean = false,
    /**
     * Linux only: popup overlay of another window (wl_subsurface on Wayland —
     * client-positionable; parent-relative coordinates). Ignored elsewhere.
     */
    popupFor: TaoWindow? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    /**
     * Materialise Compose Popup layers as native transparent windows
     * (NSPanel / WS_POPUP HWND / Tao popup window on Linux — override-
     * redirect on X11, `wl_subsurface` on Wayland) instead of drawing them
     * inline in this window's render target.
     */
    nativePopupLayers: Boolean = false,
    macOSStyle: MacOSStyle = MacOSStyle.Classic,
    /**
     * Hide this window from the OS taskbar/Dock while keeping it visible and
     * focusable.
     *
     * - macOS: switches the shared `NSApplication` to accessory activation
     *   policy (no Dock icon, no menu bar). App-wide — the last window to apply
     *   it wins.
     * - Windows: drops this window's taskbar button and Alt+Tab entry
     *   (`WS_EX_TOOLWINDOW`). Per-window.
     * - Linux: drops this window's taskbar and pager entries (GTK
     *   skip-taskbar/skip-pager hints, `_NET_WM_STATE_SKIP_TASKBAR`).
     *   Per-window; effective on X11 and XWayland, no-op on native Wayland,
     *   which has no client-side taskbar opt-out.
     *
     * Applied at window creation.
     */
    hiddenFromDock: Boolean = false,
    // Parent composition locals bridged into this window's own ComposeScene from
    // the first composition (see [openDecoratedWindow]). Defaults to null for
    // top-level windows; [DecoratedDialog] forwards its parent's locals here.
    compositionLocalContext: CompositionLocalContext? = null,
    content: @Composable TaoDecoratedWindowScope.() -> Unit,
) {
    val latestOnClose by rememberUpdatedState(onCloseRequest)
    val latestPreview by rememberUpdatedState(onPreviewKeyEvent)
    val latestKey by rememberUpdatedState(onKeyEvent)
    val latestContent by rememberUpdatedState(content)
    val latestState by rememberUpdatedState(state)
    val latestWindowBackgroundArgb =
        rememberUpdatedState(
            LocalDecoratedWindowStyle.current.colors.background
                .toArgb(),
        )

    state.inflateToMinimumSize(minimumSize)
    state.applyMacOsInitialMaximizedSize()

    // Mirrors Compose Desktop's `appliedState` pattern: tracks the last value
    // we wrote to the window so the native→state listeners can ignore echoes.
    val applied =
        remember {
            object {
                var size: DpSize? = null
                var position: WindowPosition? = null
                var placement: WindowPlacement? = null
                var isMinimized: Boolean? = null
            }
        }

    val window =
        remember {
            applied.size = state.size
            applied.placement = state.placement
            applied.isMinimized = state.isMinimized
            // applied.position deliberately stays null so the LaunchedEffect below
            // applies the initial state.position (Absolute, Aligned, …) on first
            // composition. Pre-stamping it would short-circuit Aligned positions
            // because the LaunchedEffect bails when `pos == applied.position`.

            val w =
                openDecoratedWindow(
                    onCloseRequest = { latestOnClose() },
                    title = title,
                    icon = icon,
                    width =
                        state.size.width.value
                            .toDouble(),
                    height =
                        state.size.height.value
                            .toDouble(),
                    minimumSize = minimumSize,
                    visible = false,
                    resizable = resizable,
                    enabled = enabled,
                    focusable = focusable,
                    alwaysOnTop = false,
                    // Apply Maximized at builder time. Fullscreen still needs
                    // a post-creation toggle because tao's `WindowBuilder` does
                    // not expose `with_fullscreen` in the same way (it takes a
                    // monitor handle which we don't have yet).
                    maximized = state.placement == WindowPlacement.Maximized,
                    isDialog = isDialog,
                    undecorated = undecorated,
                    popupFor = popupFor,
                    onPreviewKeyEvent = { latestPreview(it) },
                    onKeyEvent = { latestKey(it) },
                    nativePopupLayers = nativePopupLayers,
                    macOSStyle = macOSStyle,
                    hiddenFromDock = hiddenFromDock,
                    initialCompositionLocalContext = compositionLocalContext,
                    content = {
                        val taoWindow = window
                        val backgroundArgb = latestWindowBackgroundArgb.value
                        val clearColorState = LocalRequestedClearColor.current
                        SideEffect {
                            clearColorState?.value = backgroundArgb
                            taoWindow.setBackgroundColor(backgroundArgb)
                        }
                        latestContent.invoke(this)
                    },
                )

            // Initial placement / minimised flag are applied imperatively here
            // (Maximized is handled at builder time, above).
            // Position is handled by the LaunchedEffect on `state.position` to
            // cover both Absolute and Aligned variants uniformly.
            when (state.placement) {
                WindowPlacement.Maximized -> Unit
                WindowPlacement.Fullscreen -> w.setFullscreen(true)
                WindowPlacement.Floating -> Unit
            }
            if (state.isMinimized) {
                w.setMinimized(true)
            }

            // Native → state sync (resize / move). Read scale per-event since the
            // user can move the window between displays of differing densities.
            w.onResized { wPx, hPx ->
                // Windows reports a 0x0 client area while minimized. Do not
                // persist that transient value into the public WindowState.
                if (wPx <= 0 || hPx <= 0) return@onResized
                val scale = (NativeTaoBridge.nativeScaleFactor(w.handle).coerceAtLeast(1)) / 1000f
                val newSize = DpSize((wPx / scale).dp, (hPx / scale).dp)
                if (newSize != applied.size) {
                    applied.size = newSize
                    latestState.size = newSize
                }
                // Tao doesn't emit a dedicated "placement changed" event, but
                // every fullscreen / maximize / restore transition resizes the
                // window. Re-query both flags here to keep `state.placement` in
                // sync when the user exits fullscreen via Esc / green button or
                // hits the system maximize gesture.
                val placementNow =
                    when {
                        w.isFullscreen -> WindowPlacement.Fullscreen
                        w.isMaximized -> WindowPlacement.Maximized
                        else -> WindowPlacement.Floating
                    }
                if (placementNow != applied.placement) {
                    applied.placement = placementNow
                    latestState.placement = placementNow
                }
            }
            w.onMoved { xPx, yPx ->
                val scale = (NativeTaoBridge.nativeScaleFactor(w.handle).coerceAtLeast(1)) / 1000f
                val newPos = WindowPosition((xPx / scale).dp, (yPx / scale).dp)
                if (newPos != applied.position) {
                    applied.position = newPos
                    latestState.position = newPos
                }
            }
            // OS-driven minimize/restore. Set `applied` before `state` so the
            // state→window LaunchedEffect treats this as an echo and skips
            // re-issuing setMinimized().
            w.onMinimizedChanged { minimized ->
                if (minimized != applied.isMinimized) {
                    applied.isMinimized = minimized
                    latestState.isMinimized = minimized
                }
            }
            w
        }

    DisposableEffect(window) {
        onDispose { window.requestClose() }
    }

    // ── State → window sync ──
    // `resizable` is consumed at builder time above; re-apply runtime changes
    // so driving the parameter matches the AWT backends' behaviour (#260).
    LaunchedEffect(window, resizable) {
        if (window.isResizable != resizable) {
            window.setResizable(resizable)
        }
    }
    LaunchedEffect(window, state.size, state.placement) {
        // Maximized / Fullscreen windows derive their size from the
        // OS-managed placement, not from `state.size`. Skip the
        // `setInnerSize` call so we don't shrink the window back to the
        // requested logical size while Win32/Wayland think it should
        // fill the monitor.
        if (state.placement != WindowPlacement.Floating) return@LaunchedEffect
        if (state.size != applied.size) {
            window.setInnerSize(
                state.size.width.value
                    .toDouble(),
                state.size.height.value
                    .toDouble(),
            )
            applied.size = state.size
        }
    }
    LaunchedEffect(window, state.position, state.placement) {
        // Same reasoning as size: an Aligned(Center) request would move
        // the maximized window away from the work area's origin (Win32
        // happily moves a WS_MAXIMIZE window) — visible as a maximized
        // window offset diagonally from the screen corner.
        if (state.placement != WindowPlacement.Floating) return@LaunchedEffect
        val pos = state.position
        if (pos == applied.position) return@LaunchedEffect
        when (pos) {
            is WindowPosition.Absolute -> {
                window.setOuterPosition(pos.x.value.toDouble(), pos.y.value.toDouble())
                applied.position = pos
            }
            is WindowPosition.Aligned -> {
                // Use max(state.size, minimumSize) so the centring math matches
                // the size the window will actually occupy on screen — Tao
                // grows the window to honour `minimumSize` asynchronously, and
                // `state.size` still holds the (smaller) requested size at this
                // point.
                val effectiveSize = effectiveAlignedSize(state.size, minimumSize)
                if (applyAlignedPosition(window, pos, effectiveSize)) {
                    applied.position = pos
                }
            }
            else -> Unit // PlatformDefault: leave whatever Tao chose
        }
    }
    LaunchedEffect(window, state.placement) {
        if (state.placement != applied.placement) {
            // Always exit any active fullscreen/maximized state before applying
            // the new placement — Tao on macOS animates the fullscreen
            // transition and stacking the two calls without ordering can leave
            // the window in a wedged state.
            when (applied.placement) {
                WindowPlacement.Fullscreen -> window.setFullscreen(false)
                WindowPlacement.Maximized -> window.setMaximized(false)
                else -> Unit
            }
            when (state.placement) {
                WindowPlacement.Maximized -> window.setMaximized(true)
                WindowPlacement.Fullscreen -> window.setFullscreen(true)
                WindowPlacement.Floating -> Unit // already cleared above
            }
            applied.placement = state.placement
        }
    }
    LaunchedEffect(window, state.isMinimized) {
        if (state.isMinimized != applied.isMinimized) {
            window.setMinimized(state.isMinimized)
            applied.isMinimized = state.isMinimized
        }
    }

    // ── Other reactive params ──
    LaunchedEffect(window, title) { window.setTitle(title) }
    LaunchedEffect(window, alwaysOnTop) { window.setAlwaysOnTop(alwaysOnTop) }
    LaunchedEffect(window, focusable) { window.setFocusable(focusable) }
    LaunchedEffect(window, visible) {
        if (visible) {
            window.show()
            // The JVM splash screen auto-closes when the first AWT Window becomes
            // visible. The Tao backend never creates AWT windows, so we close it
            // explicitly here — same semantic: "first app window is now shown".
            try {
                java.awt.SplashScreen
                    .getSplashScreen()
                    ?.close()
            } catch (_: Throwable) {
                // No-op in headless or non-splash environments.
            }
        } else {
            window.hide()
        }
    }
    // Dock-icon reference counting (macOS): a window that isn't hiddenFromDock
    // contributes to the app's Dock icon while it is visible, so a menu-bar /
    // agent app (nucleusApplication(dockIconFollowsWindows = true)) surfaces in
    // the Dock only while a real window is on screen. Idle unless that opt-in is
    // active; [TaoStandalonePopup] tray popups never route through here.
    if (Platform.Current == Platform.MacOS && !hiddenFromDock) {
        DisposableEffect(visible) {
            if (visible) TaoDockPolicy.onWindowShown()
            onDispose { if (visible) TaoDockPolicy.onWindowHidden() }
        }
    }
    LaunchedEffect(window, minimumSize) {
        if (minimumSize != null) {
            window.setMinimumSize(
                minimumSize.width.value.toDouble(),
                minimumSize.height.value.toDouble(),
            )
        } else {
            window.setMinimumSize(null, null)
        }
    }
    LaunchedEffect(window, icon) {
        if (icon != null) {
            icon.toRgbaIcon()?.let { (w, h, px) -> window.setIcon(w, h, px) }
        } else {
            window.setIcon(0, 0, ByteArray(0))
        }
    }
}

/**
 * Mirrors the AWT backend's first-frame maximized fix on macOS.
 *
 * The native work area is reported in physical pixels, but [WindowState.size]
 * is public Compose API and must stay in dp / macOS points.
 */
@Composable
private fun WindowState.applyMacOsInitialMaximizedSize() {
    remember(this) {
        if (
            Platform.Current == Platform.MacOS &&
            placement == WindowPlacement.Maximized &&
            NativeTaoMacOsDecoBridge.isLoaded
        ) {
            val workArea = NativeTaoMacOsDecoBridge.nativeGetPrimaryMonitorWorkArea()
            val scale = primaryMacOsScaleFactor().toDouble()
            if (workArea != null && workArea.size == 4 && scale > 0.0) {
                val width = (workArea[2] / scale).roundToInt()
                val height = (workArea[3] / scale).roundToInt()
                if (width > 0 && height > 0) {
                    size = DpSize(width.dp, height.dp)
                }
            }
        }
    }
}

/**
 * Resolves a [WindowPosition.Aligned] against the primary monitor's work area
 * and pushes the resulting outer position to [window]. Returns `true` when the
 * position could be applied, `false` when the platform / native bridge is
 * unavailable.
 *
 * Supported on Windows (`nucleus_tao_windows_deco.dll`), macOS
 * (`libnucleus_tao_macos_deco.dylib`) and Linux (via the GDK-backed entry
 * points on the main `libnucleus_tao.so`). All three bridges expose the work
 * area as `[x, y, w, h]` in physical pixels with a top-left origin, so the
 * dp math below is platform-agnostic.
 *
 * On macOS [size] is treated as a fallback only — the actual NSWindow outer
 * size is queried via `nativeGetWindowRect`. Tao's `set_min_inner_size`
 * enforces a synchronous resize when the requested size is smaller than the
 * `DecoratedWindow` `minimumSize`, which lands in the Tao queue *before*
 * the LE pump runs the position effect; trusting `state.size` (still
 * holding the `rememberWindowState()` default until the resulting `Resized`
 * event makes it back to the JVM) would centre the window using a size that
 * doesn't match what's on screen and produce a visible mid-show jump.
 */
@Suppress("ReturnCount")
private fun applyAlignedPosition(
    window: TaoWindow,
    position: WindowPosition.Aligned,
    size: DpSize,
): Boolean {
    // The native window doesn't exist yet at first composition (Tao creates
    // it asynchronously on its event loop), so we always read the primary
    // monitor's scale directly — that's the monitor Tao will place the
    // window on by default, and it's the scale that pairs with the work-area
    // rect we just queried.
    val (workArea, scaleMilli) =
        when (Platform.Current) {
            Platform.Windows -> {
                if (!NativeTaoWindowsDecoBridge.isLoaded) return false
                val wa = NativeTaoWindowsDecoBridge.nativeGetPrimaryMonitorWorkArea() ?: return false
                wa to NativeTaoWindowsDecoBridge.nativeGetPrimaryMonitorScaleMilli().coerceAtLeast(1000)
            }
            Platform.MacOS -> {
                if (!NativeTaoMacOsDecoBridge.isLoaded) return false
                val wa = NativeTaoMacOsDecoBridge.nativeGetPrimaryMonitorWorkArea() ?: return false
                wa to NativeTaoMacOsDecoBridge.nativeGetPrimaryMonitorScaleMilli().coerceAtLeast(1000)
            }
            Platform.Linux -> {
                if (!NativeTaoBridge.isLoaded) return false
                warnIfWaylandIgnoresPosition(window)
                val wa = NativeTaoBridge.nativeLinuxPrimaryMonitorWorkArea(window.handle) ?: return false
                wa to NativeTaoBridge.nativeLinuxPrimaryMonitorScaleMilli(window.handle).coerceAtLeast(1000)
            }
            else -> return false
        }
    val scale = scaleMilli / 1000.0

    // Convert the work area to logical pixels so we can offset by the
    // dp-valued window size directly.
    val workXDp = workArea[0] / scale
    val workYDp = workArea[1] / scale
    val workWDp = (workArea[2] / scale).toInt()
    val workHDp = (workArea[3] / scale).toInt()

    val (winWDp, winHDp) =
        actualWindowSizeDp(window, scale)
            ?: (
                size.width.value
                    .toInt()
                    .coerceAtLeast(0) to
                    size.height.value
                        .toInt()
                        .coerceAtLeast(0)
            )

    val offset: IntOffset =
        position.alignment.align(
            size = IntSize(winWDp, winHDp),
            space = IntSize(workWDp, workHDp),
            layoutDirection = LayoutDirection.Ltr,
        )
    window.setOuterPosition(workXDp + offset.x, workYDp + offset.y)
    return true
}

private fun effectiveAlignedSize(
    size: DpSize,
    minimumSize: DpSize?,
): DpSize {
    if (minimumSize == null) return size
    val w = if (size.width.value < minimumSize.width.value) minimumSize.width else size.width
    val h = if (size.height.value < minimumSize.height.value) minimumSize.height else size.height
    return DpSize(w, h)
}

/**
 * Inflates [WindowState.size] before Tao creates the native window.
 *
 * This mirrors the AWT backend's first-frame fix. On native Wayland, clients
 * cannot force an absolute toplevel position, so the compositor places the
 * window at map time. Creating the window at the smaller default size and
 * applying [minimumSize] afterward can make that compositor placement visibly
 * shift or land off-centre. Updating [WindowState.size] up-front gives the
 * compositor the final geometry from the first map.
 */
@Composable
private fun WindowState.inflateToMinimumSize(minimumSize: DpSize?) {
    remember(this, minimumSize) {
        if (minimumSize != null) {
            val w = size.width
            val h = size.height
            if (w < minimumSize.width || h < minimumSize.height) {
                size = DpSize(maxOf(w, minimumSize.width), maxOf(h, minimumSize.height))
            }
        }
    }
}

/**
 * One-shot warning emitted the first time a [WindowPosition.Aligned] is
 * resolved on a native Wayland session: the Wayland xdg-shell protocol forbids
 * clients from setting the absolute position of a toplevel, so the centring
 * math below runs but Tao's `set_outer_position` is a no-op — the compositor
 * keeps full authority over placement. Set `NUCLEUS_TAO_LINUX_RENDERER=x11`
 * (or `GDK_BACKEND=x11`) to fall back to XWayland if precise positioning is
 * required.
 *
 * The window's backend is derived from [NativeTaoBridge.nativeLinuxHandles]
 * (slot 0: 1 = Xlib, 2 = Wayland) so we don't have to second-guess GDK env
 * vars or the auto-pick logic in `event_loop.rs`.
 */
private val waylandPositionWarned =
    java.util.concurrent.atomic
        .AtomicBoolean(false)

private fun warnIfWaylandIgnoresPosition(window: TaoWindow) {
    if (waylandPositionWarned.get()) return
    val handles = NativeTaoBridge.nativeLinuxHandles(window.handle) ?: return
    if (handles.isEmpty() || handles[0] != 2L) return
    if (!waylandPositionWarned.compareAndSet(false, true)) return
    System.err.println(
        "[DecoratedWindow] WindowPosition.Aligned ignored on native Wayland: " +
            "the xdg-shell protocol does not allow clients to set toplevel " +
            "positions; the compositor decides placement. Set " +
            "NUCLEUS_TAO_LINUX_RENDERER=x11 to fall back to XWayland.",
    )
}

/**
 * Reads the realised NSWindow outer size in dp via the macOS deco bridge, or
 * `null` when the window isn't yet on screen / the bridge is unavailable /
 * the platform doesn't expose a window-rect query.
 *
 * Used by [applyAlignedPosition] to defeat the `state.size` ↔ actual-size
 * skew introduced by Tao's `set_min_inner_size` (see that function's
 * doc-comment).
 */
private fun actualWindowSizeDp(
    window: TaoWindow,
    scale: Double,
): Pair<Int, Int>? {
    if (Platform.Current != Platform.MacOS) return null
    val nsView = window.nativeHandle
    if (nsView == 0L) return null
    val rect = NativeTaoMacOsDecoBridge.nativeGetWindowRect(nsView) ?: return null
    val w = (rect[2] / scale).toInt()
    val h = (rect[3] / scale).toInt()
    if (w <= 0 || h <= 0) return null
    return w to h
}
