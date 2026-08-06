package dev.nucleusframework.window

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.TaoWindow

/**
 * Declares this component as a window drag region: an unconsumed primary
 * press followed by a move starts the native interactive window move
 * (`performWindowDragWithEvent:` on macOS, `WM_NCLBUTTONDOWN`/`HTCAPTION` on
 * Windows, a compositor move grab on Linux).
 *
 * This is the same handler the built-in `TitleBar` installs on its whole
 * surface, exposed as a standalone modifier so custom chrome (a design
 * system's toolbar or headerbar composed via [WindowScaffold]) opts into
 * dragging declaratively. Interactive children (buttons, text fields) opt out
 * automatically by consuming the press event — identical to the `TitleBar`
 * contract.
 *
 * Must be used inside a Tao `DecoratedWindow` content tree; outside of one
 * (no [LocalTaoWindow]) the modifier is a no-op.
 *
 * Prefer the overload that takes an explicit [TaoWindow] when composing
 * chrome that already holds the window handle (e.g. [BasicTitleBar]) — that
 * path does not depend on [LocalTaoWindow] and stays correct if parent-window
 * CompositionLocals are bridged into a secondary scene.
 */
@OptIn(ExperimentalComposeUiApi::class)
public fun Modifier.windowDragArea(
    enabled: Boolean = true,
    doubleClickAction: WindowDoubleClickAction = WindowDoubleClickAction.ToggleMaximize,
): Modifier =
    composed {
        val window = LocalTaoWindow.current
        if (!enabled || window == null) return@composed Modifier
        windowDragArea(window = window, doubleClickAction = doubleClickAction)
    }

/**
 * Same as [windowDragArea] but binds drag/maximize to the given [window]
 * instead of [LocalTaoWindow]. Use from title-bar chrome that already
 * resolves the window via [DecoratedWindowScope].
 */
@OptIn(ExperimentalComposeUiApi::class)
public fun Modifier.windowDragArea(
    window: TaoWindow,
    doubleClickAction: WindowDoubleClickAction = WindowDoubleClickAction.ToggleMaximize,
): Modifier =
    composed {
        val viewConfig = LocalViewConfiguration.current
        var lastPress by remember { mutableLongStateOf(0L) }
        Modifier
            .titleBarHitTestHandler(window)
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Final) {
                if (doubleClickAction == WindowDoubleClickAction.None) return@onPointerEvent
                // Suppress double-click → toggle-maximize while fullscreen: on
                // macOS `[NSWindow zoom:]` would exit fullscreen unexpectedly.
                if (window.isFullscreen) return@onPointerEvent
                // Touch has no PointerButton — a single touch contact is the
                // touch-equivalent of a primary click (Linux/Wayland is the
                // only backend routing title-bar touch to Compose).
                val isPrimaryOrTouch =
                    this.currentEvent.button == PointerButton.Primary ||
                        (
                            Platform.Current == Platform.Linux &&
                                this.currentEvent.changes.any { it.type == PointerType.Touch }
                        )
                if (isPrimaryOrTouch && this.currentEvent.changes.any { !it.isConsumed }) {
                    val now = System.currentTimeMillis()
                    if (now - lastPress in
                        viewConfig.doubleTapMinTimeMillis..viewConfig.doubleTapTimeoutMillis &&
                        (window.isMaximized || window.isResizable)
                    ) {
                        window.setMaximized(!window.isMaximized)
                        // Cancel any in-flight touch drag armed with the
                        // pre-toggle maximize state.
                        window.cancelWindowsTitleBarTouchDrag()
                    }
                    lastPress = now
                }
            }
    }

/**
 * Opts this subtree out of any ancestor [windowDragArea]: presses landing
 * here never start a window move.
 *
 * A [windowDragArea] treats an unconsumed press as "drag the window", which
 * interactive children normally cancel by consuming it. Gesture detectors
 * that only claim the pointer once it *moves* — scrollbars, sliders, resize
 * handles, anything built on `awaitFirstDown(requireUnconsumed = false)` —
 * leave the press unconsumed, so the window would start moving before they
 * take over. Wrap them with this modifier instead of relying on consumption
 * timing.
 *
 * The press is consumed in the Main pass so an ancestor [windowDragArea]
 * (which arms on Final, after Main) sees it already claimed. Descendants of
 * this modifier still receive the press on Main first (Main is root → leaf),
 * so the wrapped component keeps working normally.
 */
@OptIn(ExperimentalComposeUiApi::class)
public fun Modifier.noWindowDrag(): Modifier =
    onPointerEvent(PointerEventType.Press, PointerEventPass.Main) { event ->
        event.changes.forEach { it.consume() }
    }

/**
 * Marks this layout so Compose hit testing continues to the siblings BELOW it
 * instead of stopping at it — the layout still receives every pointer event.
 *
 * Backs [WindowScaffold]'s `TitleBarPlacement.Overlay(passThroughToContent = true)`:
 * the title bar is composed on top of the window content, and by default the
 * topmost sibling wins the hit test for its whole bounds, so interactive content
 * merged into the title-bar band (e.g. a collapsed navigation pane's
 * back/hamburger buttons) would never receive pointer events at all. With this
 * marker the content below is hit-tested too; when it consumes a press (Main
 * pass), the bar's own drag handler sees the consumption (Final pass) and does
 * not start a window move.
 *
 * Opt-in only: applied unconditionally it makes a click on the opaque chrome
 * activate whatever clickable content happens to flow underneath it.
 *
 * The sibling-sharing flag only acts at the level of the layout node whose own
 * modifier chain carries it (`InnerNodeCoordinator` consults the immediate
 * child's chain), which is why the scaffold applies it to the wrapper box of
 * the bar slot — a flag deeper inside the bar's subtree would not propagate.
 */
internal fun Modifier.shareHitTestWithSiblings(): Modifier = this then ShareHitTestWithSiblingsElement

private data object ShareHitTestWithSiblingsElement : ModifierNodeElement<ShareHitTestWithSiblingsNode>() {
    override fun create(): ShareHitTestWithSiblingsNode = ShareHitTestWithSiblingsNode()

    override fun update(node: ShareHitTestWithSiblingsNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "shareHitTestWithSiblings"
    }
}

private class ShareHitTestWithSiblingsNode :
    Modifier.Node(),
    PointerInputModifierNode {
    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) = Unit

    override fun onCancelPointerInput() = Unit

    override fun sharePointerInputWithSiblings(): Boolean = true
}
