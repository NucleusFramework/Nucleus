package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.jetbrains.JBR
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.WindowMouseEventEffect
import io.github.kdroidfilter.nucleus.window.utils.macos.MacUtil

fun Modifier.newFullscreenControls(newControls: Boolean = true): Modifier =
    this then
        NewFullscreenControlsElement(
            newControls,
            debugInspectorInfo {
                name = "newFullscreenControls"
                value = newControls
            },
        )

private class NewFullscreenControlsElement(
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

private class NewFullscreenControlsNode(
    var newControls: Boolean,
) : Modifier.Node()

@Suppress("FunctionNaming")
@Composable
internal fun DecoratedWindowScope.MacOSTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    val newFullscreenControls =
        modifier.foldOut(false) { e, r ->
            if (e is NewFullscreenControlsElement) {
                e.newControls
            } else {
                r
            }
        }

    if (newFullscreenControls) {
        System.setProperty("apple.awt.newFullScreenControls", true.toString())
        System.setProperty(
            "apple.awt.newFullScreenControls.background",
            "${style.colors.fullscreenControlButtonsBackground.toArgb()}",
        )
        MacUtil.updateColors(window)
    } else {
        System.clearProperty("apple.awt.newFullScreenControls")
        System.clearProperty("apple.awt.newFullScreenControls.background")
    }

    val titleBar = remember { JBR.getWindowDecorations().createCustomTitleBar() }

    WindowMouseEventEffect(titleBar)

    val controlDir = controlButtonsDirection.resolve()
    val controlIsRtl = controlDir == LayoutDirection.Rtl
    val controlsSide = if (controlIsRtl) WindowControlsSide.Start else WindowControlsSide.End

    CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
        TitleBarImpl(
            modifier = modifier,
            gradientStartColor = gradientStartColor,
            style = style,
            controlButtonsDirection = controlDir,
            layoutPolicy = layoutPolicy,
            applyTitleBar = { height, titleBarState ->
                titleBar.putProperty("controls.rtl", controlIsRtl)
                titleBar.height = height.value
                JBR.getWindowDecorations().setCustomTitleBar(window, titleBar)

                val padding =
                    if (titleBarState.isFullscreen && newFullscreenControls) {
                        if (controlIsRtl) {
                            PaddingValues(end = 80.dp)
                        } else {
                            PaddingValues(start = 80.dp)
                        }
                    } else {
                        PaddingValues(start = titleBar.leftInset.dp, end = titleBar.rightInset.dp)
                    }
                padding
            },
            onPlace = {
                if (state.isFullscreen) {
                    MacUtil.updateFullScreenButtons(window)
                }
            },
            backgroundContent = backgroundContent,
            content = content,
        )
    }
}
