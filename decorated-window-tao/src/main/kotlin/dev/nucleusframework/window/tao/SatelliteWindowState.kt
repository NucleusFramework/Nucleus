package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * State of a [SatelliteWindow]: the geometry inputs the app owns, plus the
 * live anchoring state the window publishes back.
 *
 * Create it with [rememberSatelliteWindowState] inside composition, or
 * directly when it has to outlive a single composition (a palette whose
 * position must survive being toggled off and on).
 *
 * @param size the satellite's requested size.
 * @param positioner where the satellite lands relative to its parent, applied
 *   once when the window is first shown (and again on [reanchor]).
 * @param anchorRect the rectangle the [positioner] anchors to, in the parent's
 *   own coordinate space (top-left of the parent frame = origin). `null`
 *   anchors to the whole parent frame, decorations included.
 */
public class SatelliteWindowState(
    size: DpSize = DpSize(DEFAULT_SATELLITE_WIDTH_DP.dp, DEFAULT_SATELLITE_HEIGHT_DP.dp),
    positioner: WindowPositioner = WindowPositioner(),
    anchorRect: DpRect? = null,
) {
    /** Requested satellite size. Reactive: writing it resizes the window. */
    public var size: DpSize by mutableStateOf(size)

    /**
     * Placement rule. Deliberately *not* snapshot state: placement is a
     * one-shot (see [SatelliteWindow]), so a new rule only takes effect on the
     * next [reanchor].
     */
    public var positioner: WindowPositioner = positioner

    /** Anchor rectangle in parent coordinates. Applied on [reanchor], like [positioner]. */
    public var anchorRect: DpRect? = anchorRect

    /**
     * The satellite's current offset from its parent's top-left corner, or
     * `null` before both windows are on screen.
     *
     * This is the value the satellite preserves as the parent moves. It is
     * re-captured whenever the user drags the satellite, so a palette the user
     * has repositioned keeps its *new* relationship to the parent.
     */
    public var offsetFromParent: DpOffset? by mutableStateOf(null)
        internal set

    /**
     * `true` while the satellite is force-hidden because its parent went
     * fullscreen or maximized. See [SatelliteWindow]'s
     * `hideWhileParentFullscreenOrMaximized`.
     */
    public var isHiddenByParent: Boolean by mutableStateOf(false)
        internal set

    /** `true` while the satellite itself holds the keyboard focus. */
    public var isActive: Boolean by mutableStateOf(false)
        internal set

    internal var reanchorRequest: (() -> Unit)? = null

    /**
     * Re-applies [positioner] against the parent's current geometry, discarding
     * any offset the user established by dragging the satellite.
     *
     * Placement is otherwise a one-shot: like Flutter's satellite archetype,
     * the satellite keeps whatever offset it has so the user's own positioning
     * is never overridden. Call this after changing [positioner] or
     * [anchorRect], or when the UI element the satellite documents has moved.
     *
     * No-op when the satellite is not (yet) on screen.
     */
    public fun reanchor() {
        reanchorRequest?.invoke()
    }
}

/** Remembers a [SatelliteWindowState] across recompositions. */
@Composable
public fun rememberSatelliteWindowState(
    size: DpSize = DpSize(DEFAULT_SATELLITE_WIDTH_DP.dp, DEFAULT_SATELLITE_HEIGHT_DP.dp),
    positioner: WindowPositioner = WindowPositioner(),
    anchorRect: DpRect? = null,
): SatelliteWindowState = remember { SatelliteWindowState(size, positioner, anchorRect) }

internal const val DEFAULT_SATELLITE_WIDTH_DP = 320
internal const val DEFAULT_SATELLITE_HEIGHT_DP = 240
