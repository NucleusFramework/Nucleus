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

/**
 * [value] dp pulled back inside the range a window can be placed in, with half of
 * [MAX_OUTER_POSITION_DP] kept as headroom for the parent origin added on the way to the platform
 * (a native Wayland popup's content origin). For windows that follow the pointer — a drag ghost
 * goes wherever the pointer goes, far off every display included — so an excursion lands at the
 * nearest placeable spot instead of tripping the caller-bug guard in `setOuterPosition`. Not for
 * positions an app asked for: those must still fail loudly. NaN is left as is; it names no spot.
 */
internal fun clampOuterPosition(value: Double): Double =
    value.coerceIn(-MAX_OUTER_POSITION_DP / 2, MAX_OUTER_POSITION_DP / 2)
