package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.painter.Painter
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
import io.github.kdroidfilter.nucleus.core.runtime.Platform

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
@Suppress("LongParameterList", "FunctionNaming", "LongMethod")
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
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    macOSStyle: MacOSStyle = MacOSStyle.Classic,
    content: @Composable TaoDecoratedWindowScope.() -> Unit,
) {
    val latestOnClose by rememberUpdatedState(onCloseRequest)
    val latestPreview by rememberUpdatedState(onPreviewKeyEvent)
    val latestKey by rememberUpdatedState(onKeyEvent)
    val latestContent by rememberUpdatedState(content)
    val latestState by rememberUpdatedState(state)

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
                    onPreviewKeyEvent = { latestPreview(it) },
                    onKeyEvent = { latestKey(it) },
                    macOSStyle = macOSStyle,
                    content = { latestContent.invoke(this) },
                )

            // Initial placement / minimised flag are applied imperatively here.
            // Position is handled by the LaunchedEffect on `state.position` to
            // cover both Absolute and Aligned variants uniformly.
            when (state.placement) {
                WindowPlacement.Maximized -> w.setMaximized(true)
                WindowPlacement.Fullscreen -> w.setFullscreen(true)
                WindowPlacement.Floating -> Unit
            }
            if (state.isMinimized) {
                w.setMinimized(true)
            }

            // Native → state sync (resize / move). Read scale per-event since the
            // user can move the window between displays of differing densities.
            w.onResized { wPx, hPx ->
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
            w
        }

    DisposableEffect(window) {
        onDispose { window.requestClose() }
    }

    // ── State → window sync ──
    LaunchedEffect(window, state.size) {
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
    LaunchedEffect(window, state.position) {
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
        if (visible) window.show() else window.hide()
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
