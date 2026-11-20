package dev.nucleusframework.window.tao

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.registerOnLayoutRectChanged
import androidx.compose.ui.node.DelegatableNode.RegistrationHandle
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/**
 * A cheaper [androidx.compose.ui.layout.onGloballyPositioned]: [callback] gets
 * this modifier's coordinates only when its position can have changed, not on
 * every placement of anything above it (#560).
 *
 * Two triggers, because neither covers the other:
 * - `registerOnLayoutRectChanged` (no throttle, no debounce: inline on the
 *   scene thread, right after layout) — fires when the *layout node's* rect
 *   moves in the window, ancestors' layers included. It knows nothing of where
 *   this modifier sits in the chain.
 * - `onPlaced` — fires when this node is laid out again, which is how a change
 *   *inside* the chain (a `padding` before this modifier) reaches it.
 *
 * The coordinates are this modifier's, exactly as `onGloballyPositioned`
 * reported them, so the callback reads `boundsInWindow()` (clipping included)
 * or `positionInRoot()` unchanged. It may run twice for one change and never
 * for a pure layer transform set *earlier in the same chain*; callers that push
 * to native code dedup on the value they push.
 */
internal fun Modifier.onPositionChanged(callback: (LayoutCoordinates) -> Unit): Modifier =
    this then OnPositionChangedElement(callback)

private data class OnPositionChangedElement(
    val callback: (LayoutCoordinates) -> Unit,
) : ModifierNodeElement<OnPositionChangedNode>() {
    override fun create(): OnPositionChangedNode = OnPositionChangedNode(callback)

    override fun update(node: OnPositionChangedNode) {
        node.callback = callback
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "onPositionChanged"
    }
}

private class OnPositionChangedNode(
    var callback: (LayoutCoordinates) -> Unit,
) : Modifier.Node(),
    LayoutAwareModifierNode {
    private var coordinates: LayoutCoordinates? = null
    private var handle: RegistrationHandle? = null

    override fun onAttach() {
        handle =
            registerOnLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) {
                coordinates?.takeIf { it.isAttached }?.let(callback)
            }
    }

    override fun onDetach() {
        handle?.unregister()
        handle = null
        coordinates = null
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        callback(coordinates)
    }
}
