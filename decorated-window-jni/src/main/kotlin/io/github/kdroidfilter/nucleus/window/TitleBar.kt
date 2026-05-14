package io.github.kdroidfilter.nucleus.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.debugInspectorInfo
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle

/**
 * Platform-aware title bar for [DecoratedWindow].
 *
 * @param controlButtonsDirection Controls which side the window control buttons
 *   (close, minimize, maximize) are placed on, independently of the title bar
 *   content direction. Defaults to [ControlButtonsDirection.Auto] which follows
 *   the Compose [LocalLayoutDirection][androidx.compose.ui.platform.LocalLayoutDirection].
 */
@Suppress("FunctionNaming")
@Composable
fun DecoratedWindowScope.TitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    BasicTitleBar(
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlButtonsDirection,
        layoutPolicy = TitleBarLayoutPolicy.Default,
        backgroundContent = backgroundContent,
        content = content,
    )
}

@Suppress("FunctionNaming")
@Composable
fun DecoratedWindowScope.BasicTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    when (Platform.Current) {
        Platform.Linux ->
            LinuxTitleBar(
                modifier,
                gradientStartColor,
                style,
                controlButtonsDirection,
                layoutPolicy,
                backgroundContent,
                content,
            )
        Platform.Windows ->
            WindowsTitleBar(
                modifier,
                gradientStartColor,
                style,
                controlButtonsDirection,
                layoutPolicy,
                backgroundContent,
                content,
            )
        Platform.MacOS ->
            MacOSTitleBar(
                modifier,
                gradientStartColor,
                style,
                controlButtonsDirection,
                layoutPolicy,
                backgroundContent,
                content,
            )
        Platform.Unknown ->
            error("TitleBar is not supported on this platform(${System.getProperty("os.name")})")
    }
}

fun Modifier.newFullscreenControls(newControls: Boolean = true): Modifier =
    this then
        NewFullscreenControlsElement(
            newControls,
            debugInspectorInfo {
                name = "newFullscreenControls"
                value = newControls
            },
        )

internal class NewFullscreenControlsElement(
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

internal class NewFullscreenControlsNode(
    var newControls: Boolean,
) : Modifier.Node()

internal fun Modifier.hasNewFullscreenControls(): Boolean =
    foldOut(false) { e, r ->
        if (e is NewFullscreenControlsElement) e.newControls else r
    }

fun Modifier.macOSLargeCornerRadius(enabled: Boolean = true): Modifier =
    this then
        MacOSLargeCornerRadiusElement(
            enabled,
            debugInspectorInfo {
                name = "macOSLargeCornerRadius"
                value = enabled
            },
        )

internal class MacOSLargeCornerRadiusElement(
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

internal class MacOSLargeCornerRadiusNode(
    var enabled: Boolean,
) : Modifier.Node()

internal fun Modifier.hasMacOSLargeCornerRadius(): Boolean =
    foldOut(false) { e, r ->
        if (e is MacOSLargeCornerRadiusElement) e.enabled else r
    }
