package dev.nucleusframework.window.tao.event

import dev.nucleusframework.window.tao.TaoPointerScrollEvent

/**
 * Mirrors AWT's common three-lines-per-notch default, which
 * [dev.nucleusframework.window.tao.TaoWindow] reports as `scrollAmount` on
 * Linux `SCROLL_LINE`. The X11 standalone panel emits discrete Button4–7
 * clicks already in Compose's sign convention (no extra negate).
 *
 * Do not "harmonize" this to 1 to match Windows: Compose's `LinuxGtkConfig`
 * multiplies by `MouseWheelEvent.scrollAmount`, while Windows uses
 * [TaoWindowsScrollConfig] which applies the ×3 itself and expects
 * `scrollAmount = 1`.
 */
internal const val LINUX_AWT_SCROLL_AMOUNT_DEFAULT: Int = 3

/** Discrete X11 Button4–7 click already in Compose's sign convention. */
internal fun linuxWheelToAwtScrollEvent(
    dx: Float,
    dy: Float,
): TaoPointerScrollEvent =
    TaoPointerScrollEvent(
        dxAwt = dx,
        dyAwt = dy,
        scrollAmount = LINUX_AWT_SCROLL_AMOUNT_DEFAULT,
    )
