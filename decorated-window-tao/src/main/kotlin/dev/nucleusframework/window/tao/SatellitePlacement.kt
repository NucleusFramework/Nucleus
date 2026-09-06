package dev.nucleusframework.window.tao

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** Edge of a window's content area a docked satellite attaches to. */
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
     * @property side the edge the panel attaches to.
     * @property order position among the panels docked on the same side, low
     *   to high from the top (left/right sides) or the left (top/bottom sides).
     */
    public data class Docked(
        val side: DockSide,
        val order: Int = 0,
    ) : SatellitePlacement
}

private const val DEFAULT_GAP_DP = 12
