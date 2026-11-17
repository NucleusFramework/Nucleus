package dev.nucleusframework.tray.app

import androidx.compose.ui.window.WindowPosition
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * AppKit parks a brand-new NSStatusItem at the menu-bar's left edge until the
 * status bar assigns it a slot. Menu extras always live on the right, so a
 * relative X below this threshold means "not laid out yet" — treating it as a
 * real anchor puts the popup at the top-left of the screen.
 */
internal const val MAC_STATUS_ITEM_MIN_RELATIVE_X_PX = 24

/**
 * Waits until [read] yields a usable, stable tray-anchored window position.
 *
 * The historical poller returned the first non-[WindowPosition.PlatformDefault]
 * value. On macOS that is often `Absolute(0, 0)` / the work-area origin: the
 * status item exists but has not been assigned a menu-bar slot yet, so any
 * `initiallyVisible` TrayApp opens at the top-left on most launches.
 */
internal suspend fun awaitAnchoredWindowPosition(
    timeoutMs: Long = 3_000L,
    pollDelayMs: Long = 50L,
    minStableReads: Int = 2,
    isUsable: (WindowPosition) -> Boolean = { isUsableAnchorPosition(it) },
    delayMs: suspend (Long) -> Unit = { delay(it) },
    read: suspend () -> WindowPosition,
): WindowPosition {
    require(minStableReads >= 1)
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastUsable: WindowPosition? = null
    var stableCount = 0
    var lastRead: WindowPosition = WindowPosition.PlatformDefault

    while (System.currentTimeMillis() < deadline) {
        val pos = read()
        lastRead = pos
        if (isUsable(pos)) {
            if (lastUsable != null && sameAbsolute(lastUsable, pos)) {
                stableCount++
                if (stableCount + 1 >= minStableReads) return pos
            } else {
                lastUsable = pos
                stableCount = 0
                if (minStableReads <= 1) return pos
            }
        } else {
            lastUsable = null
            stableCount = 0
        }
        delayMs(pollDelayMs)
    }
    return lastUsable ?: lastRead
}

internal fun isLaidOutMacStatusItem(
    x: Int,
    @Suppress("UNUSED_PARAMETER") y: Int,
    screen: ScreenRect,
): Boolean = (x - screen.x) >= MAC_STATUS_ITEM_MIN_RELATIVE_X_PX

internal fun isUsableAnchorPosition(
    pos: WindowPosition,
    workArea: ScreenRect? = null,
): Boolean {
    if (pos !is WindowPosition.Absolute) return false
    if (pos.x.value == 0f && pos.y.value == 0f) return false
    return workArea == null || abs(pos.x.value - workArea.x) >= 1.5f
}

private fun sameAbsolute(
    a: WindowPosition,
    b: WindowPosition,
): Boolean {
    if (a !is WindowPosition.Absolute || b !is WindowPosition.Absolute) return a == b
    return abs(a.x.value - b.x.value) < 1f && abs(a.y.value - b.y.value) < 1f
}
