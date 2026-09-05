package dev.nucleusframework.window.tao.popup

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect

/**
 * Screen geometry a native popup layer needs to place itself against the
 * *display* rather than against its owner window (#569).
 *
 * Compose decides a popup's position entirely in window-rooted coordinates.
 * `Popup.skiko.kt` flips and clips inside `[0, containerSize]`, where
 * `containerSize` is whatever the layer's own composition reports through
 * `LocalWindowInfo` — the layers answer with the work area, so a popup lays out
 * at full size and flips against a screen-sized box. But that box is *rooted at
 * the window's content top-left*: a virtual screen, correct only while the
 * content origin happens to coincide with the work-area origin (roughly:
 * maximized on the primary display). Everywhere else a `DropdownMenu` near the
 * real screen edge lands offscreen.
 *
 * [popupScreenClampOffset] closes that gap at the single choke point where each
 * layer pushes its native frame, using the two pieces of information the
 * platform has but Compose never sees: where the owner's content sits on
 * screen, and where the displays' work areas are.
 *
 * The window-rooted box has one consequence the clamp cannot undo: a popup can
 * never be placed *above or left of* the owner's content origin, because
 * `clipPosition` coerces the position into `[0, …]` there. Fixing that means
 * intercepting `PopupPositionProvider.calculatePosition` (which receives the
 * anchor in window coordinates), i.e. owning the `Popup` composable the way
 * Jewel's `LocalPopupRenderer` does — see #569.
 */
internal class PopupScreenGeometry(
    /**
     * Owner window's **content** origin in global screen physical pixels,
     * top-left origin — the same space [workAreasPx] is expressed in. This is
     * the origin the layers' window-rooted frames are implicitly relative to.
     */
    val parentContentOriginPx: IntOffset,
    /**
     * Work area (display minus taskbar / menu bar / dock / panels) of every
     * attached display, in global screen physical pixels. A list rather than
     * the owner's display alone: a popup anchored near the edge of a window
     * that straddles two displays belongs to the display *it* lands on, which
     * is not necessarily the one hosting the window's centre.
     */
    val workAreasPx: List<IntRect>,
)
