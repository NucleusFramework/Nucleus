package io.github.kdroidfilter.nucleus.window

import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.debugInspectorInfo

/**
 * Opt-in to the new fullscreen-controls behavior on macOS.
 *
 * When applied to a [TitleBar], the title bar slides as a top overlay during
 * native fullscreen instead of staying anchored at the top of the layout.
 * Backend-specific:
 * - **JBR**: drives `apple.awt.newFullScreenControls` and AppKit traffic-light
 *   recentering via JBR's CustomTitleBar.
 * - **JNI**: hooks the AppKit menu bar monitor to animate the offset.
 * - **Tao**: reserved tag — tao's native fullscreen already animates the
 *   menu bar separately; this flag is read by tao's TitleBar for symmetry
 *   with the AWT backends.
 *
 * No-op on Linux and Windows.
 */
fun Modifier.newFullscreenControls(newControls: Boolean = true): Modifier =
    this then
        NewFullscreenControlsElement(
            newControls,
            debugInspectorInfo {
                name = "newFullscreenControls"
                value = newControls
            },
        )

class NewFullscreenControlsElement(
    val newControls: Boolean,
    val inspectorInfo: InspectorInfo.() -> Unit,
) : ModifierNodeElement<NewFullscreenControlsNode>() {
    override fun create(): NewFullscreenControlsNode = NewFullscreenControlsNode(newControls)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val otherModifier = other as? NewFullscreenControlsElement ?: return false
        return newControls == otherModifier.newControls
    }

    override fun hashCode(): Int = newControls.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        inspectorInfo()
    }

    override fun update(node: NewFullscreenControlsNode) {
        node.newControls = newControls
    }
}

class NewFullscreenControlsNode(
    var newControls: Boolean,
) : Modifier.Node()

fun Modifier.hasNewFullscreenControls(): Boolean =
    foldOut(false) { e, r ->
        if (e is NewFullscreenControlsElement) e.newControls else r
    }

/**
 * Opt-in to the macOS Sequoia (26+) large window corner radius (≈ 12 pt).
 *
 * Backend-specific:
 * - **JBR**: installs a hidden NSToolbar via JBR's CustomTitleBar so AppKit
 *   applies the larger corner-radius style.
 * - **JNI**: same NSToolbar install path via the JNI bridge.
 * - **Tao**: alternative path is the [io.github.kdroidfilter.nucleus.window.tao.MacOSStyle]
 *   enum on `openDecoratedWindow`; the modifier is read by tao's TitleBar for
 *   parity but the window-creation enum takes precedence.
 *
 * No-op on Linux and Windows, and on macOS pre-Sequoia.
 */
fun Modifier.macOSLargeCornerRadius(enabled: Boolean = true): Modifier =
    this then
        MacOSLargeCornerRadiusElement(
            enabled,
            debugInspectorInfo {
                name = "macOSLargeCornerRadius"
                value = enabled
            },
        )

class MacOSLargeCornerRadiusElement(
    val enabled: Boolean,
    val inspectorInfo: InspectorInfo.() -> Unit,
) : ModifierNodeElement<MacOSLargeCornerRadiusNode>() {
    override fun create(): MacOSLargeCornerRadiusNode = MacOSLargeCornerRadiusNode(enabled)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val otherModifier = other as? MacOSLargeCornerRadiusElement ?: return false
        return enabled == otherModifier.enabled
    }

    override fun hashCode(): Int = enabled.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        inspectorInfo()
    }

    override fun update(node: MacOSLargeCornerRadiusNode) {
        node.enabled = enabled
    }
}

class MacOSLargeCornerRadiusNode(
    var enabled: Boolean,
) : Modifier.Node()

fun Modifier.hasMacOSLargeCornerRadius(): Boolean =
    foldOut(false) { e, r ->
        if (e is MacOSLargeCornerRadiusElement) e.enabled else r
    }
