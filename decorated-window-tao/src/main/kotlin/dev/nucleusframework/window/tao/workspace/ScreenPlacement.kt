package dev.nucleusframework.window.tao.workspace

import dev.nucleusframework.window.tao.TaoWindow
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

private val warnedFeatures = ConcurrentHashMap.newKeySet<String>()

/** Same JUL logger `TaoWindow` reports its other Wayland gaps on. */
private val waylandLogger: Logger = Logger.getLogger("dev.nucleusframework.window.tao.wayland")

/**
 * Logs once per process and per [feature] that the feature is unavailable on
 * this window because it has no client-side screen placement. A no-op where
 * [canPlaceOnScreen] holds.
 *
 * Per process rather than per window: the windows these features live in —
 * floating satellites, torn-off tab windows — are created and destroyed with
 * every dock, undock and merge, and one line is enough to explain the missing
 * gesture.
 */
internal fun TaoWindow.warnScreenPlacementUnsupported(feature: String) {
    if (canPlaceOnScreen || !warnedFeatures.add(feature)) return
    waylandLogger.warning(
        "$feature needs client-side screen placement, which native Wayland (xdg-shell) does not offer: " +
            "a client can neither read its windows' screen position nor move them. The built-in grips " +
            "carry the gesture over the platform drag-and-drop session instead; " +
            "run with NUCLEUS_TAO_LINUX_RENDERER=x11 (XWayland) for the screen-space API.",
    )
}
