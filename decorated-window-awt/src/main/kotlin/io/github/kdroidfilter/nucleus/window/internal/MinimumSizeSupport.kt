package io.github.kdroidfilter.nucleus.window.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.yield

private const val MAX_FRAME_WAIT_ITERATIONS = 8

/**
 * Inflates [WindowState.size] up-front so Compose centers the window at the
 * already-final dimensions. Without this, applying [java.awt.Window.minimumSize]
 * after Compose has centered the window would re-anchor the frame at its
 * bottom-left corner and visibly shift it (most visible on macOS).
 *
 * We mutate state during composition — normally an anti-pattern — but
 * `SideEffect {}` runs after Window has already read state.size, which is
 * too late to influence the initial centering. The mutation is idempotent
 * (guarded by a size compare) and only re-runs when [state] or [minimumSize]
 * changes, so it never loops.
 *
 * Pair this with [InstallMinimumSizeAfterCentering] inside the Window content
 * to enforce the constraint at the AWT level.
 */
@Composable
public fun WindowState.inflateToMinimumSize(minimumSize: DpSize?) {
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
 * Installs [java.awt.Window.minimumSize] after Compose Desktop has applied
 * [WindowState.size] / position to the AWT frame.
 *
 * We poll for the frame to reach the target dimensions instead of relying on
 * a single `yield()`. The yield-once approach worked on Compose Desktop 1.10
 * because the internal `update` block committed `state.size` synchronously
 * before the next coroutine resume — but that ordering is an implementation
 * detail and could break on a future version. Polling makes the fix robust:
 * we wait until AWT actually has the size we expect, with a small cap so we
 * never loop forever in pathological cases.
 *
 * AWT bounds are in logical pixels (= Dp). Do NOT convert via
 * [androidx.compose.ui.unit.Density.roundToPx] — that applies the screen
 * scale factor and would double the size on Retina/HiDPI displays.
 */
@Composable
public fun FrameWindowScope.InstallMinimumSizeAfterCentering(minimumSize: DpSize?) {
    if (minimumSize == null) return
    LaunchedEffect(window, minimumSize) {
        val targetW = minimumSize.width.value.toInt()
        val targetH = minimumSize.height.value.toInt()
        var attempts = 0
        while ((window.width < targetW || window.height < targetH) &&
            attempts < MAX_FRAME_WAIT_ITERATIONS
        ) {
            yield()
            attempts++
        }
        window.minimumSize = java.awt.Dimension(targetW, targetH)
    }
}
