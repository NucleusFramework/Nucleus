package dev.nucleusframework.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.styling.TitleBarStyle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

private const val GRADIENT_MIDPOINT = 0.5f

public val LocalContentColor: ProvidableCompositionLocal<Color> =
    staticCompositionLocalOf { Color.Black }

/**
 * The resolved layout direction for window control buttons.
 * Provided by [GenericTitleBarImpl] so that control button composables
 * can apply this as [LocalLayoutDirection] around their content,
 * independently of the app's content direction.
 */
public val LocalControlButtonsDirection: ProvidableCompositionLocal<LayoutDirection> =
    staticCompositionLocalOf { LayoutDirection.Ltr }

@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun GenericTitleBarImpl(
    state: DecoratedWindowState,
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: LayoutDirection = LocalLayoutDirection.current,
    controlButtonsPlacementDirection: LayoutDirection = controlButtonsDirection,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    applyTitleBar: (Dp, DecoratedWindowState) -> PaddingValues,
    onPlace: (() -> Unit)? = null,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit,
) {
    val titleBarInfo = LocalTitleBarInfo.current

    val background by style.colors.backgroundFor(state)

    val density = LocalDensity.current

    val backgroundBrush =
        remember(background, gradientStartColor) {
            if (gradientStartColor.isUnspecified) {
                SolidColor(background)
            } else {
                with(density) {
                    Brush.horizontalGradient(
                        0.0f to background,
                        GRADIENT_MIDPOINT to gradientStartColor,
                        1.0f to background,
                        startX = style.metrics.gradientStartX.toPx(),
                        endX = style.metrics.gradientEndX.toPx(),
                    )
                }
            }
        }

    Box(
        modifier =
            modifier
                .background(backgroundBrush)
                .then(
                    // Block focus on Windows/Linux so Tab navigation cannot enter the Compose-driven
                    // title bar drag area. On macOS the traffic-light buttons are native (outside the
                    // Compose hit-test area), and focus must remain enabled so TextField/TextArea
                    // children in the title bar can receive keyboard input (issue #206 / PR #208).
                    if (Platform.Current == Platform.MacOS) {
                        Modifier
                    } else {
                        Modifier.focusProperties { canFocus = false }
                    },
                ).layoutId(TITLE_BAR_LAYOUT_ID)
                .height(style.metrics.height)
                .onSizeChanged { with(density) { applyTitleBar(it.height.toDp(), state) } }
                .fillMaxWidth(),
    ) {
        backgroundContent()
        Layout(
            content = {
                CompositionLocalProvider(
                    LocalContentColor provides style.colors.content,
                    // [controlButtonsDirection] drives the *order* of the control
                    // buttons (close/minimize/maximize) within their group.
                    LocalControlButtonsDirection provides controlButtonsDirection,
                ) {
                    val scope = TitleBarScopeImpl(titleBarInfo.title, titleBarInfo.icon)
                    scope.content(state)
                }
            },
            modifier = Modifier.fillMaxSize().onPlaced { onPlace?.invoke() },
            measurePolicy =
                rememberTitleBarMeasurePolicy(
                    state = state,
                    applyTitleBar = applyTitleBar,
                    // [controlButtonsPlacementDirection] drives the *side* the
                    // control-button group is placed on, independently of its
                    // internal order. Defaults to [controlButtonsDirection].
                    controlButtonsDirection = controlButtonsPlacementDirection,
                    layoutPolicy = layoutPolicy,
                ),
        )
    }
}

@Stable
public interface TitleBarScope {
    public val title: String

    public val icon: Painter?

    public fun Modifier.align(alignment: Alignment.Horizontal): Modifier

    /**
     * Click handler for title bar elements that works reliably in macOS
     * fullscreen on non-notch screens.
     *
     * Standard [clickable][androidx.compose.foundation.clickable] requires a
     * complete Press -> Release (tap) gesture. On some JDK/macOS combinations,
     * the system injects phantom pointer-exit events between Press and Release
     * in fullscreen, which cancels the tap gesture and prevents `onClick` from
     * firing.
     *
     * This modifier fires [onClick] on the **press** event instead, making it
     * immune to phantom exit events. It is the recommended replacement for
     * `clickable` on interactive elements placed inside a title bar.
     */
    public fun Modifier.titleBarClickable(onClick: () -> Unit): Modifier
}

public class TitleBarScopeImpl(
    override val title: String,
    override val icon: Painter?,
) : TitleBarScope {
    @Suppress("MaxLineLength")
    override fun Modifier.align(alignment: Alignment.Horizontal): Modifier =
        this then TitleBarChildDataElement(alignment)

    override fun Modifier.titleBarClickable(onClick: () -> Unit): Modifier =
        pointerInput(onClick) {
            val ctx = currentCoroutineContext()
            awaitPointerEventScope {
                while (ctx.isActive) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.type == PointerEventType.Press) {
                        val change = event.changes.firstOrNull() ?: continue
                        if (!change.isConsumed) {
                            change.consume()
                            onClick()
                        }
                    }
                }
            }
        }
}

public class TitleBarChildDataElement(
    public val horizontalAlignment: Alignment.Horizontal,
) : ModifierNodeElement<TitleBarChildDataNode>() {
    override fun create(): TitleBarChildDataNode = TitleBarChildDataNode(horizontalAlignment)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val otherModifier = other as? TitleBarChildDataElement ?: return false
        return horizontalAlignment == otherModifier.horizontalAlignment
    }

    override fun hashCode(): Int = horizontalAlignment.hashCode()

    override fun update(node: TitleBarChildDataNode) {
        node.horizontalAlignment = horizontalAlignment
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "align"
        value = horizontalAlignment
    }
}

public class TitleBarChildDataNode(
    public var horizontalAlignment: Alignment.Horizontal,
) : Modifier.Node(),
    ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): TitleBarChildDataNode = this@TitleBarChildDataNode
}
