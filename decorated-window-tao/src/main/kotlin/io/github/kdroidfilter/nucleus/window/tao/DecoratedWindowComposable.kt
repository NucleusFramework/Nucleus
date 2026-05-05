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
    val applied = remember {
        object {
            var size: DpSize? = null
            var position: WindowPosition? = null
            var placement: WindowPlacement? = null
            var isMinimized: Boolean? = null
        }
    }

    val window = remember {
        applied.size = state.size
        applied.placement = state.placement
        applied.isMinimized = state.isMinimized
        // applied.position deliberately stays null so the LaunchedEffect below
        // applies the initial state.position (Absolute, Aligned, …) on first
        // composition. Pre-stamping it would short-circuit Aligned positions
        // because the LaunchedEffect bails when `pos == applied.position`.

        val w = openDecoratedWindow(
            onCloseRequest = { latestOnClose() },
            title = title,
            icon = icon,
            width = state.size.width.value.toDouble(),
            height = state.size.height.value.toDouble(),
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
            val placementNow = when {
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
            window.setInnerSize(state.size.width.value.toDouble(), state.size.height.value.toDouble())
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
                if (applyAlignedPosition(window, pos, state.size)) {
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
 * Currently Windows-only: relies on `nucleus_tao_windows_deco.dll` to query
 * the work area. macOS and Linux fall back to whatever default position Tao
 * picked at creation.
 */
private fun applyAlignedPosition(
    window: TaoWindow,
    position: WindowPosition.Aligned,
    size: DpSize,
): Boolean {
    if (Platform.Current != Platform.Windows) return false
    if (!NativeTaoWindowsDecoBridge.isLoaded) return false
    val workArea = NativeTaoWindowsDecoBridge.nativeGetPrimaryMonitorWorkArea() ?: return false

    // The native HWND doesn't exist yet at first composition (Tao creates it
    // asynchronously on its event loop), so we always read the primary
    // monitor's scale directly — that's the monitor Tao will place the
    // window on by default, and it's the scale that pairs with the work-area
    // rect we just queried.
    val scaleMilli = NativeTaoWindowsDecoBridge.nativeGetPrimaryMonitorScaleMilli().coerceAtLeast(1000)
    val scale = scaleMilli / 1000.0

    // Convert the work area to logical pixels so we can offset by the
    // dp-valued window size directly.
    val workXDp = workArea[0] / scale
    val workYDp = workArea[1] / scale
    val workWDp = (workArea[2] / scale).toInt()
    val workHDp = (workArea[3] / scale).toInt()

    val winWDp = size.width.value.toInt().coerceAtLeast(0)
    val winHDp = size.height.value.toInt().coerceAtLeast(0)

    val offset: IntOffset = position.alignment.align(
        size = IntSize(winWDp, winHDp),
        space = IntSize(workWDp, workHDp),
        layoutDirection = LayoutDirection.Ltr,
    )
    window.setOuterPosition(workXDp + offset.x, workYDp + offset.y)
    return true
}
