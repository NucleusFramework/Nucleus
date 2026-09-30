package dev.nucleusframework.window.tao

import kotlin.math.abs

/**
 * The farthest a window's outer position may be from the screen origin, in dp: past any real
 * desktop, yet well inside every platform's integer coordinates once scaled (AppKit refuses a
 * frame outside the 32-bit range, Win32 and GTK store positions as `int`).
 */
internal const val MAX_OUTER_POSITION_DP: Double = 1_000_000.0

/** Whether ([x], [y]) dp can be handed to the platform as a window's outer position. */
internal fun isPlaceableOuterPosition(
    x: Double,
    y: Double,
): Boolean = x.isFinite() && y.isFinite() && abs(x) <= MAX_OUTER_POSITION_DP && abs(y) <= MAX_OUTER_POSITION_DP
