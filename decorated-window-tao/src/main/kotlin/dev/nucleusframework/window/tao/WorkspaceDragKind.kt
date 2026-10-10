package dev.nucleusframework.window.tao

import dev.nucleusframework.window.ExperimentalNucleusApi

/**
 * How a cross-window drag in flight is carried — see [SatelliteWorkspace.dragKind]
 * and [TabWorkspace.dragKind].
 */
@ExperimentalNucleusApi
public enum class WorkspaceDragKind {
    /**
     * A window follows the pointer: the satellite's own, the tab's own when it
     * is the only one in it, or a ghost window standing in for a docked panel
     * or a tab leaving its strip — [SatelliteWorkspace.dragGhost] and
     * [TabWorkspace.dragGhost] are published for the latter.
     */
    Window,

    /**
     * The platform's drag-and-drop session carries it, because the window
     * cannot be placed by the app ([TaoWindow.canPlaceOnScreen] `false`). The
     * compositor draws a picture of the dragged panel or tab as the drag icon,
     * nothing of the workspace's follows the pointer, and the source is not
     * told where it is: the window under it resolves the drop and the source
     * acts on that record.
     */
    Transfer,
}
