package dev.nucleusframework.window.tao.workspace

import dev.nucleusframework.window.tao.TaoWindow
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Whether this window's screen position can be read and set by the client —
 * the two primitives every cross-window gesture is built on (a drag resolved
 * in screen pixels, a drop hit-tested against another window, a satellite
 * following its owner).
 *
 * `false` on a native Wayland surface: xdg-shell gives the compositor full
 * authority over toplevel placement, so GDK reports every toplevel at `(0, 0)`
 * and ignores `gtk_window_move`. [TaoWindow.outerBoundsPx] still carries a
 * valid *size* there, which is why callers that only need one keep using it;
 * anything that would treat its origin as a screen coordinate must check this
 * first. X11, XWayland (`NUCLEUS_TAO_LINUX_RENDERER=x11`), Windows and macOS
 * all place.
 */
internal val TaoWindow.supportsScreenPlacement: Boolean
    get() = !isNativeWaylandSurface

private val warnedFeatures = ConcurrentHashMap.newKeySet<String>()

/** Same JUL logger `TaoWindow` reports its other Wayland gaps on. */
private val waylandLogger: Logger = Logger.getLogger("dev.nucleusframework.window.tao.wayland")

/**
 * Logs once per process and per [feature] that the feature is unavailable on
 * this window because it has no client-side screen placement. A no-op where
 * [supportsScreenPlacement] holds.
 *
 * Per process rather than per window: the windows these features live in —
 * floating satellites, torn-off tab windows — are created and destroyed with
 * every dock, undock and merge, and one line is enough to explain the missing
 * gesture.
 */
internal fun TaoWindow.warnScreenPlacementUnsupported(feature: String) {
    if (supportsScreenPlacement || !warnedFeatures.add(feature)) return
    waylandLogger.warning(
        "$feature needs client-side screen placement, which native Wayland (xdg-shell) does not offer: " +
            "a client can neither read its windows' screen position nor move them. The built-in grips " +
            "carry the gesture over the platform drag-and-drop session instead; " +
            "run with NUCLEUS_TAO_LINUX_RENDERER=x11 (XWayland) for the screen-space API.",
    )
}
