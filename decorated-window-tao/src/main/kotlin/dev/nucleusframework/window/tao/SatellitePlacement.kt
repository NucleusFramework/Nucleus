package dev.nucleusframework.window.tao

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Edge of a window's content area a docked satellite attaches to.
 *
 * Sides are **physical**: [Left] is the left edge of the screen whatever the
 * `LayoutDirection` in force, so a right-to-left app that wants its navigation
 * panels on the right says [Right]. See [DockLayout] for how the four sides
 * nest.
 */
public enum class DockSide {
    /** Left edge; the panel runs the full content height. */
    Left,

    /** Right edge; the panel runs the full content height. */
    Right,

    /** Top edge; the panel runs the full content width. */
    Top,

    /** Bottom edge; the panel runs the full content width. */
    Bottom,
    ;

    /** `true` for [Left] and [Right], whose extent is a width. */
    public val isVertical: Boolean get() = this == Left || this == Right

    /** The edge across the content: [Left] for [Right], [Top] for [Bottom], and back. */
    public val opposite: DockSide
        get() =
            when (this) {
                Left -> Right
                Right -> Left
                Top -> Bottom
                Bottom -> Top
            }
}

/**
 * Where a satellite of a [SatelliteWorkspace] lives.
 *
 * A satellite is declared once with [Satellite] and hosted according to its
 * placement: as its own OS window ([Floating]) or inside the content of the
 * window it is docked into ([Docked]). The workspace moves satellites between
 * the two with [SatelliteWorkspace.dock] and [SatelliteWorkspace.undock];
 * `rememberSaveable` state inside the satellite survives the move.
 */
public sealed interface SatellitePlacement {
    /**
     * An OS window owned by the workspace's current owner window: anchored
     * once by [positioner], then following the owner (see [SatelliteWindow]).
     *
     * @property positioner where the window lands relative to the owner when
     *   it is first shown.
     * @property size requested window size.
     * @property anchorRect rectangle in the owner's coordinate space the
     *   [positioner] anchors to; `null` anchors to the whole owner frame.
     */
    public data class Floating(
        val positioner: WindowPositioner = DefaultPositioner,
        val size: DpSize = DefaultSize,
        val anchorRect: DpRect? = null,
    ) : SatellitePlacement {
        /** Defaults shared by every floating placement. */
        public companion object {
            /** Hangs the satellite off the owner's top-right corner with a 12 dp gap. */
            public val DefaultPositioner: WindowPositioner =
                WindowPositioner(
                    parentAnchor = WindowAnchor.TopRight,
                    childAnchor = WindowAnchor.TopLeft,
                    offset = DpOffset(DEFAULT_GAP_DP.dp, 0.dp),
                )

            /** The [SatelliteWindowState] default size. */
            public val DefaultSize: DpSize = DpSize(DEFAULT_SATELLITE_WIDTH_DP.dp, DEFAULT_SATELLITE_HEIGHT_DP.dp)
        }
    }

    /**
     * A panel composed inside a [DockLayout] of the window the satellite is
     * docked into ([SatelliteEntry.dockHost]).
     *
     * How the panels on one side share it is the layout's decision
     * (`DockLayout(layeredSides = …)`), and the two numbers here serve the two
     * arrangements: on a *split* side the panels divide the side's length in
     * proportion to their [weight] and share its thickness
     * ([SatelliteWorkspace.dockExtent]); on a *layered* side each panel is a
     * full-length layer of its own [extent], from the edge inwards. Both are
     * kept up to date by the layout's splitters and travel with the
     * [SatelliteLayoutSnapshot].
     *
     * @property side the edge the panel attaches to.
     * @property order rank among the panels docked on the same side of the
     *   same layout, low to high from the top (left/right sides) or the left
     *   (top/bottom sides) on a split side, and from the edge towards the
     *   content on a layered one. [SatelliteWorkspace.dock] and
     *   [SatelliteWorkspace.undock] keep a side's ranks contiguous from `0`
     *   and remember the rank a satellite leaves with, so it comes back to
     *   it; a declared placement's order is the position it is inserted at.
     * @property extent the panel's own thickness on a layered side — its
     *   width on [DockSide.Left] / [DockSide.Right], its height on
     *   [DockSide.Top] / [DockSide.Bottom]. `null` falls back to the side's
     *   [SatelliteWorkspace.dockExtent]; [SatelliteWorkspace.dock] seeds it
     *   from the floating window's size. Ignored on a split side.
     * @property weight the panel's share of a split side's length, relative
     *   to its neighbours. Ignored on a layered side.
     */
    public data class Docked(
        val side: DockSide,
        val order: Int = 0,
        val extent: Dp? = null,
        val weight: Float = 1f,
    ) : SatellitePlacement {
        init {
            require(weight > 0f) { "weight must be positive, was $weight" }
        }
    }
}

private const val DEFAULT_GAP_DP = 12
