package dev.nucleusframework.window.tao.popup

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect

/**
 * Offset to add to [frameInParentPx] so the popup lands fully inside the work
 * area of the display it belongs to, in the owner-window coordinate space the
 * native `setFrame` calls take.
 *
 * Clamp, not flip: a popup pushed past an edge slides back in rather than
 * re-opening on the other side of its anchor — the behaviour of most native
 * menus, and the only one reachable without intercepting
 * `PopupPositionProvider.calculatePosition` (which receives the anchor in
 * window coordinates and cannot be told about a screen origin; see #569).
 *
 * Returns [IntOffset.Zero] — i.e. exactly the pre-#569 behaviour — whenever
 * the platform cannot resolve the geometry ([geometry] is `null`, as on
 * Wayland where popups are parent-relative subsurfaces with no global
 * position), or the frame has no area yet.
 */
internal fun popupScreenClampOffset(
    frameInParentPx: IntRect,
    geometry: PopupScreenGeometry?,
): IntOffset {
    if (geometry == null) return IntOffset.Zero
    val width = frameInParentPx.width
    val height = frameInParentPx.height
    if (width <= 0 || height <= 0) return IntOffset.Zero

    val origin = geometry.parentContentOriginPx
    val left = origin.x + frameInParentPx.left
    val top = origin.y + frameInParentPx.top
    val onScreen = IntRect(left = left, top = top, right = left + width, bottom = top + height)
    val work = pickWorkArea(onScreen, origin, geometry.workAreasPx) ?: return IntOffset.Zero

    // `coerceAtMost` before `coerceAtLeast`: a popup taller or wider than the
    // work area keeps its top-left visible (where a menu's first items and a
    // tooltip's text are) instead of its bottom-right.
    val clampedLeft = onScreen.left.coerceAtMost(work.right - width).coerceAtLeast(work.left)
    val clampedTop = onScreen.top.coerceAtMost(work.bottom - height).coerceAtLeast(work.top)
    return IntOffset(clampedLeft - onScreen.left, clampedTop - onScreen.top)
}

/**
 * The display [frame] belongs to: the one it overlaps most. A frame that
 * overlaps nothing — the very case the clamp exists for — is attributed to the
 * display hosting the owner window's content origin, so the popup slides back
 * onto the display the user is looking at instead of the first one enumerated.
 */
private fun pickWorkArea(
    frame: IntRect,
    parentOrigin: IntOffset,
    areas: List<IntRect>,
): IntRect? {
    val usable = areas.filter { it.width > 0 && it.height > 0 }
    if (usable.size <= 1) return usable.firstOrNull()
    val best = usable.maxBy { overlapArea(it, frame) }
    if (overlapArea(best, frame) > 0L) return best
    return usable.firstOrNull { it.contains(parentOrigin) } ?: usable.first()
}

private fun overlapArea(
    a: IntRect,
    b: IntRect,
): Long {
    val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
    val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
    return width.toLong() * height.toLong()
}
