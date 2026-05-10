package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.offset
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.max

private const val GRADIENT_MIDPOINT = 0.5f

val LocalContentColor = staticCompositionLocalOf { Color.Black }

/**
 * The resolved layout direction for window control buttons.
 * Provided by [GenericTitleBarImpl] so that control button composables
 * can apply this as [LocalLayoutDirection] around their content,
 * independently of the app's content direction.
 */
val LocalControlButtonsDirection = staticCompositionLocalOf { LayoutDirection.Ltr }

@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun GenericTitleBarImpl(
    state: DecoratedWindowState,
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: LayoutDirection = LocalLayoutDirection.current,
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
                    LocalControlButtonsDirection provides controlButtonsDirection,
                ) {
                    val scope = TitleBarScopeImpl(titleBarInfo.title, titleBarInfo.icon)
                    scope.content(state)
                }
            },
            modifier = Modifier.fillMaxSize(),
            measurePolicy =
                rememberTitleBarMeasurePolicy(state, applyTitleBar, controlButtonsDirection, onPlace),
        )
    }
}

class TitleBarMeasurePolicy(
    private val state: DecoratedWindowState,
    private val applyTitleBar: (Dp, DecoratedWindowState) -> PaddingValues,
    private val controlButtonsDirection: LayoutDirection,
    private val onPlace: (() -> Unit)? = null,
) : MeasurePolicy {
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        if (measurables.isEmpty()) {
            return layout(width = constraints.minWidth, height = constraints.minHeight) {}
        }

        var maxSpaceVertically = constraints.minHeight
        val contentConstraints = constraints.copy(minWidth = 0, minHeight = 0)

        // Two-pass measurement: End items are measured independently so they
        // don't reduce the available width for Start/Center items.  This keeps
        // behaviour consistent with JBR where native caption buttons reserve
        // space via padding insets rather than Compose item measurement.
        val endMeasurables = mutableListOf<Pair<Measurable, Placeable>>()
        val otherMeasurables = mutableListOf<Pair<Measurable, Placeable>>()

        // Pass 1 – measure End-aligned items among themselves
        var endOccupied = 0
        for (it in measurables) {
            val alignment = (it.parentData as? TitleBarChildDataNode)?.horizontalAlignment
            if (alignment != Alignment.End) continue
            val placeable = it.measure(contentConstraints.offset(horizontal = -endOccupied))
            endOccupied += placeable.width
            maxSpaceVertically = max(maxSpaceVertically, placeable.height)
            endMeasurables += it to placeable
        }

        // Pass 2 – measure non-End items with full available width
        var otherOccupied = 0
        @Suppress("LoopWithTooManyJumpStatements")
        for (it in measurables) {
            val alignment = (it.parentData as? TitleBarChildDataNode)?.horizontalAlignment
            if (alignment == Alignment.End) continue
            val placeable = it.measure(contentConstraints.offset(horizontal = -otherOccupied))
            if (constraints.maxWidth < otherOccupied + placeable.width) break
            otherOccupied += placeable.width
            maxSpaceVertically = max(maxSpaceVertically, placeable.height)
            otherMeasurables += it to placeable
        }

        val measuredPlaceable = endMeasurables + otherMeasurables
        val boxHeight = maxSpaceVertically

        val contentPadding = applyTitleBar(boxHeight.toDp(), state)

        // Use Ltr to get absolute left/right insets.
        val leftInset = contentPadding.calculateLeftPadding(LayoutDirection.Ltr).roundToPx()
        val rightInset = contentPadding.calculateRightPadding(LayoutDirection.Ltr).roundToPx()

        val occupiedSpaceHorizontally = endOccupied + otherOccupied + leftInset + rightInset
        val boxWidth = maxOf(constraints.minWidth, occupiedSpaceHorizontally)

        return layout(boxWidth, boxHeight) {
            onPlace?.invoke()

            val placeableGroups =
                measuredPlaceable.groupBy { (measurable, _) ->
                    (measurable.parentData as? TitleBarChildDataNode)?.horizontalAlignment
                        ?: Alignment.CenterHorizontally
                }

            val contentIsRtl = layoutDirection == LayoutDirection.Rtl
            val controlsOnRight = controlButtonsDirection == LayoutDirection.Ltr

            // Absolute occupied-space tracking for each side
            var leftUsed = leftInset
            var rightUsed = rightInset

            // End items (control buttons) first — they claim the extreme edge
            // before Start items, so they always stay at their designated side
            // even when content and controls share the same edge.
            placeableGroups[Alignment.End]?.forEach { (_, placeable) ->
                val y = Alignment.CenterVertically.align(placeable.height, boxHeight)
                if (controlsOnRight) {
                    placeable.place(boxWidth - rightUsed - placeable.width, y)
                    rightUsed += placeable.width
                } else {
                    placeable.place(leftUsed, y)
                    leftUsed += placeable.width
                }
            }

            // Start items: leading edge of the content direction
            placeableGroups[Alignment.Start]?.forEach { (_, placeable) ->
                val y = Alignment.CenterVertically.align(placeable.height, boxHeight)
                if (contentIsRtl) {
                    placeable.place(boxWidth - rightUsed - placeable.width, y)
                    rightUsed += placeable.width
                } else {
                    placeable.place(leftUsed, y)
                    leftUsed += placeable.width
                }
            }

            // Center items: clamped between occupied edges
            val centerPlaceable = placeableGroups[Alignment.CenterHorizontally].orEmpty()
            val requiredCenterSpace = centerPlaceable.sumOf { it.second.width }
            val minX = leftUsed
            val maxX = boxWidth - rightUsed - requiredCenterSpace
            var centerX = (boxWidth - requiredCenterSpace) / 2

            if (minX <= maxX) {
                centerX = centerX.coerceIn(minX, maxX)
                centerPlaceable.forEach { (_, placeable) ->
                    val y = Alignment.CenterVertically.align(placeable.height, boxHeight)
                    placeable.place(centerX, y)
                    centerX += placeable.width
                }
            }
        }
    }
}

@Composable
fun rememberTitleBarMeasurePolicy(
    state: DecoratedWindowState,
    applyTitleBar: (Dp, DecoratedWindowState) -> PaddingValues,
    controlButtonsDirection: LayoutDirection = LocalLayoutDirection.current,
    onPlace: (() -> Unit)? = null,
): MeasurePolicy =
    remember(state, applyTitleBar, controlButtonsDirection, onPlace) {
        TitleBarMeasurePolicy(state, applyTitleBar, controlButtonsDirection, onPlace)
    }

@Stable
interface TitleBarScope {
    val title: String

    val icon: Painter?

    fun Modifier.align(alignment: Alignment.Horizontal): Modifier

    /**
     * Click handler for title bar elements that works reliably in macOS
     * fullscreen on non-notch screens.
     *
     * Standard [clickable][androidx.compose.foundation.clickable] requires a
     * complete Press → Release (tap) gesture. On some JDK/macOS combinations,
     * the system injects phantom pointer-exit events between Press and Release
     * in fullscreen, which cancels the tap gesture and prevents `onClick` from
     * firing.
     *
     * This modifier fires [onClick] on the **press** event instead, making it
     * immune to phantom exit events. It is the recommended replacement for
     * `clickable` on interactive elements placed inside a title bar.
     */
    fun Modifier.titleBarClickable(onClick: () -> Unit): Modifier
}

class TitleBarScopeImpl(
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

class TitleBarChildDataElement(
    val horizontalAlignment: Alignment.Horizontal,
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

class TitleBarChildDataNode(
    var horizontalAlignment: Alignment.Horizontal,
) : Modifier.Node(),
    ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?) = this@TitleBarChildDataNode
}
