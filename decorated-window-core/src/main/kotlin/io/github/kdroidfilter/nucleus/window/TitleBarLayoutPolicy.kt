package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.offset
import java.awt.Window
import kotlin.math.max

@Stable
interface TitleBarLayoutPolicy {
    fun MeasureScope.prepareMeasure(scope: TitleBarMeasureScope): TitleBarMeasureResult

    fun MeasureScope.measureTitleBar(scope: TitleBarPlacementScope): MeasureResult

    companion object {
        val Default: TitleBarLayoutPolicy = DefaultTitleBarLayoutPolicy
        val FillCenter: TitleBarLayoutPolicy = FillCenterTitleBarLayoutPolicy
    }
}

@Stable
interface TitleBarMeasureScope {
    val children: List<TitleBarLayoutChild>
    val constraints: Constraints
    val layoutDirection: LayoutDirection
    val controlButtonsDirection: LayoutDirection
}

@Stable
interface TitleBarPlacementScope {
    val children: List<TitleBarLayoutChild>
    val constraints: Constraints
    val layoutDirection: LayoutDirection
    val controlButtonsDirection: LayoutDirection
    val contentPadding: PaddingValues
    val measureResult: TitleBarMeasureResult
}

@Stable
interface TitleBarMeasureResult {
    val heightPx: Int
}

@Stable
interface TitleBarLayoutChild {
    val measurable: Measurable
    val alignment: Alignment.Horizontal
}

@Composable
fun rememberTitleBarMeasurePolicy(
    window: Window,
    state: DecoratedWindowState,
    applyTitleBar: (Dp, DecoratedWindowState) -> PaddingValues,
    controlButtonsDirection: LayoutDirection,
    layoutPolicy: TitleBarLayoutPolicy,
): MeasurePolicy =
    remember(window, state, applyTitleBar, controlButtonsDirection, layoutPolicy) {
        TitleBarLayoutAdapterMeasurePolicy(
            state = state,
            applyTitleBar = applyTitleBar,
            controlButtonsDirection = controlButtonsDirection,
            layoutPolicy = layoutPolicy,
        )
    }

@Composable
fun rememberTitleBarMeasurePolicy(
    window: Window,
    state: DecoratedWindowState,
    applyTitleBar: (Dp, DecoratedWindowState) -> PaddingValues,
    controlButtonsDirection: LayoutDirection = LocalLayoutDirection.current,
    onPlace: (() -> Unit)? = null,
): MeasurePolicy =
    remember(window, state, applyTitleBar, controlButtonsDirection, onPlace) {
        TitleBarMeasurePolicy(
            window = window,
            state = state,
            applyTitleBar = applyTitleBar,
            controlButtonsDirection = controlButtonsDirection,
            onPlace = onPlace,
        )
    }

class TitleBarMeasurePolicy(
    window: Window,
    private val state: DecoratedWindowState,
    private val applyTitleBar: (Dp, DecoratedWindowState) -> PaddingValues,
    private val controlButtonsDirection: LayoutDirection,
    private val onPlace: (() -> Unit)? = null,
) : MeasurePolicy {
    private val delegate =
        TitleBarLayoutAdapterMeasurePolicy(
            state = state,
            applyTitleBar = applyTitleBar,
            controlButtonsDirection = controlButtonsDirection,
            layoutPolicy = TitleBarLayoutPolicy.Default,
        )

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val measureResult = with(delegate) { measure(measurables, constraints) }
        return object : MeasureResult {
            override val width: Int = measureResult.width
            override val height: Int = measureResult.height
            override val alignmentLines: Map<AlignmentLine, Int> = measureResult.alignmentLines

            override fun placeChildren() {
                measureResult.placeChildren()
                onPlace?.invoke()
            }
        }
    }
}

private class TitleBarLayoutAdapterMeasurePolicy(
    private val state: DecoratedWindowState,
    private val applyTitleBar: (Dp, DecoratedWindowState) -> PaddingValues,
    private val controlButtonsDirection: LayoutDirection,
    private val layoutPolicy: TitleBarLayoutPolicy,
) : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        if (measurables.isEmpty()) {
            return layout(width = constraints.minWidth, height = constraints.minHeight) {}
        }

        val children =
            measurables.map { measurable ->
                TitleBarLayoutChildImpl(
                    measurable = measurable,
                    alignment =
                        (measurable.parentData as? TitleBarChildDataNode)?.horizontalAlignment
                            ?: Alignment.CenterHorizontally,
                )
            }

        val prepareScope =
            TitleBarMeasureScopeImpl(
                children = children,
                constraints = constraints,
                layoutDirection = layoutDirection,
                controlButtonsDirection = controlButtonsDirection,
            )
        val measureResult = with(layoutPolicy) { prepareMeasure(prepareScope) }
        val contentPadding = applyTitleBar(measureResult.heightPx.toDp(), state)

        val placementScope =
            TitleBarPlacementScopeImpl(
                children = children,
                constraints = constraints,
                layoutDirection = layoutDirection,
                controlButtonsDirection = controlButtonsDirection,
                contentPadding = contentPadding,
                measureResult = measureResult,
            )
        return with(layoutPolicy) { measureTitleBar(placementScope) }
    }
}

private data class TitleBarLayoutChildImpl(
    override val measurable: Measurable,
    override val alignment: Alignment.Horizontal,
) : TitleBarLayoutChild

private data class TitleBarMeasureScopeImpl(
    override val children: List<TitleBarLayoutChild>,
    override val constraints: Constraints,
    override val layoutDirection: LayoutDirection,
    override val controlButtonsDirection: LayoutDirection,
) : TitleBarMeasureScope

private data class TitleBarPlacementScopeImpl(
    override val children: List<TitleBarLayoutChild>,
    override val constraints: Constraints,
    override val layoutDirection: LayoutDirection,
    override val controlButtonsDirection: LayoutDirection,
    override val contentPadding: PaddingValues,
    override val measureResult: TitleBarMeasureResult,
) : TitleBarPlacementScope

private data class MeasuredLayoutChild(
    val child: TitleBarLayoutChild,
    val placeable: Placeable,
)

private object DefaultTitleBarLayoutPolicy : TitleBarLayoutPolicy {
    override fun MeasureScope.prepareMeasure(scope: TitleBarMeasureScope): TitleBarMeasureResult {
        var maxSpaceVertically = scope.constraints.minHeight
        val contentConstraints = scope.constraints.copy(minWidth = 0, minHeight = 0)

        // Two-pass measurement: End items are measured independently so they
        // do not reduce the available width for Start/Center items.
        val endMeasurables = mutableListOf<MeasuredLayoutChild>()
        val otherMeasurables = mutableListOf<MeasuredLayoutChild>()

        var endOccupied = 0
        for (child in scope.children) {
            if (child.alignment != Alignment.End) continue
            val placeable = child.measurable.measure(contentConstraints.offset(horizontal = -endOccupied))
            endOccupied += placeable.width
            maxSpaceVertically = max(maxSpaceVertically, placeable.height)
            endMeasurables += MeasuredLayoutChild(child, placeable)
        }

        var otherOccupied = 0
        @Suppress("LoopWithTooManyJumpStatements")
        for (child in scope.children) {
            if (child.alignment == Alignment.End) continue
            val placeable = child.measurable.measure(contentConstraints.offset(horizontal = -otherOccupied))
            if (scope.constraints.maxWidth < otherOccupied + placeable.width) break
            otherOccupied += placeable.width
            maxSpaceVertically = max(maxSpaceVertically, placeable.height)
            otherMeasurables += MeasuredLayoutChild(child, placeable)
        }

        return DefaultTitleBarMeasureResult(
            heightPx = maxSpaceVertically,
            measuredPlaceables = endMeasurables + otherMeasurables,
            endOccupied = endOccupied,
            otherOccupied = otherOccupied,
        )
    }

    override fun MeasureScope.measureTitleBar(scope: TitleBarPlacementScope): MeasureResult {
        val result =
            scope.measureResult as? DefaultTitleBarMeasureResult
                ?: error("TitleBarLayoutPolicy.Default requires DefaultTitleBarMeasureResult")

        val leftInset = scope.contentPadding.calculateLeftPadding(LayoutDirection.Ltr).roundToPx()
        val rightInset = scope.contentPadding.calculateRightPadding(LayoutDirection.Ltr).roundToPx()

        val occupiedSpaceHorizontally = result.endOccupied + result.otherOccupied + leftInset + rightInset
        val boxWidth = maxOf(scope.constraints.minWidth, occupiedSpaceHorizontally)
        val boxHeight = result.heightPx

        return layout(boxWidth, boxHeight) {
            val placeableGroups = result.measuredPlaceables.groupBy { it.child.alignment }

            val contentIsRtl = scope.layoutDirection == LayoutDirection.Rtl
            val controlsOnRight = scope.controlButtonsDirection == LayoutDirection.Ltr

            var leftUsed = leftInset
            var rightUsed = rightInset

            placeableGroups[Alignment.End].orEmpty().forEach { measured ->
                val placeable = measured.placeable
                val y = Alignment.CenterVertically.align(placeable.height, boxHeight)
                if (controlsOnRight) {
                    placeable.place(boxWidth - rightUsed - placeable.width, y)
                    rightUsed += placeable.width
                } else {
                    placeable.place(leftUsed, y)
                    leftUsed += placeable.width
                }
            }

            placeableGroups[Alignment.Start].orEmpty().forEach { measured ->
                val placeable = measured.placeable
                val y = Alignment.CenterVertically.align(placeable.height, boxHeight)
                if (contentIsRtl) {
                    placeable.place(boxWidth - rightUsed - placeable.width, y)
                    rightUsed += placeable.width
                } else {
                    placeable.place(leftUsed, y)
                    leftUsed += placeable.width
                }
            }

            val centerPlaceables = placeableGroups[Alignment.CenterHorizontally].orEmpty()
            val requiredCenterSpace = centerPlaceables.sumOf { it.placeable.width }
            val minX = leftUsed
            val maxX = boxWidth - rightUsed - requiredCenterSpace
            var centerX = (boxWidth - requiredCenterSpace) / 2

            if (minX <= maxX) {
                centerX = centerX.coerceIn(minX, maxX)
                centerPlaceables.forEach { measured ->
                    val placeable = measured.placeable
                    val y = Alignment.CenterVertically.align(placeable.height, boxHeight)
                    placeable.place(centerX, y)
                    centerX += placeable.width
                }
            }
        }
    }
}

private data class DefaultTitleBarMeasureResult(
    override val heightPx: Int,
    val measuredPlaceables: List<MeasuredLayoutChild>,
    val endOccupied: Int,
    val otherOccupied: Int,
) : TitleBarMeasureResult

private object FillCenterTitleBarLayoutPolicy : TitleBarLayoutPolicy {
    override fun MeasureScope.prepareMeasure(scope: TitleBarMeasureScope): TitleBarMeasureResult {
        var maxSpaceVertically = scope.constraints.minHeight
        val contentConstraints = scope.constraints.copy(minWidth = 0, minHeight = 0)

        val endMeasurables = mutableListOf<MeasuredLayoutChild>()
        val startMeasurables = mutableListOf<MeasuredLayoutChild>()
        var endOccupied = 0
        var startOccupied = 0
        var centerCount = 0
        var centerMeasurable: Measurable? = null

        for (child in scope.children) {
            when (child.alignment) {
                Alignment.End -> {
                    val placeable = child.measurable.measure(contentConstraints.offset(horizontal = -endOccupied))
                    endOccupied += placeable.width
                    maxSpaceVertically = max(maxSpaceVertically, placeable.height)
                    endMeasurables += MeasuredLayoutChild(child, placeable)
                }

                Alignment.Start -> {
                    val placeable = child.measurable.measure(contentConstraints.offset(horizontal = -startOccupied))
                    startOccupied += placeable.width
                    maxSpaceVertically = max(maxSpaceVertically, placeable.height)
                    startMeasurables += MeasuredLayoutChild(child, placeable)
                }

                Alignment.CenterHorizontally -> {
                    centerCount += 1
                    centerMeasurable = child.measurable
                }
            }
        }

        require(centerCount <= 1) {
            "TitleBarLayoutPolicy.FillCenter supports at most one " +
                "Alignment.CenterHorizontally child, but found $centerCount."
        }

        if (centerMeasurable != null && scope.constraints.minHeight != scope.constraints.maxHeight) {
            // Compose only allows a Measurable to be measured once in a layout pass.
            // Use intrinsic height here and defer the actual measurement to measureTitleBar.
            val centerHeightCandidate = centerMeasurable.maxIntrinsicHeight(scope.constraints.maxWidth)
            maxSpaceVertically = max(maxSpaceVertically, centerHeightCandidate)
        }

        return FillCenterTitleBarMeasureResult(
            heightPx = maxSpaceVertically,
            startMeasuredPlaceables = startMeasurables,
            endMeasuredPlaceables = endMeasurables,
            startOccupied = startOccupied,
            endOccupied = endOccupied,
            centerMeasurable = centerMeasurable,
        )
    }

    override fun MeasureScope.measureTitleBar(scope: TitleBarPlacementScope): MeasureResult {
        val result =
            scope.measureResult as? FillCenterTitleBarMeasureResult
                ?: error("TitleBarLayoutPolicy.FillCenter requires FillCenterTitleBarMeasureResult")

        val leftInset = scope.contentPadding.calculateLeftPadding(LayoutDirection.Ltr).roundToPx()
        val rightInset = scope.contentPadding.calculateRightPadding(LayoutDirection.Ltr).roundToPx()
        val occupiedSpaceHorizontally = result.startOccupied + result.endOccupied + leftInset + rightInset
        val boxWidth = maxOf(scope.constraints.minWidth, occupiedSpaceHorizontally)

        val contentIsRtl = scope.layoutDirection == LayoutDirection.Rtl
        val controlsOnRight = scope.controlButtonsDirection == LayoutDirection.Ltr

        var leftUsed = leftInset
        var rightUsed = rightInset

        result.endMeasuredPlaceables.forEach { measured ->
            if (controlsOnRight) {
                rightUsed += measured.placeable.width
            } else {
                leftUsed += measured.placeable.width
            }
        }

        result.startMeasuredPlaceables.forEach { measured ->
            if (contentIsRtl) {
                rightUsed += measured.placeable.width
            } else {
                leftUsed += measured.placeable.width
            }
        }

        val centerLaneX = leftUsed
        val centerLaneWidth = maxOf(0, boxWidth - leftUsed - rightUsed)
        val centerPlaceable =
            result.centerMeasurable?.measure(
                Constraints(
                    minWidth = centerLaneWidth,
                    maxWidth = centerLaneWidth,
                    minHeight = 0,
                    maxHeight = scope.constraints.maxHeight,
                ),
            )
        val boxHeight = max(result.heightPx, centerPlaceable?.height ?: 0)

        return layout(boxWidth, boxHeight) {
            var leftPlaced = leftInset
            var rightPlaced = rightInset

            result.endMeasuredPlaceables.forEach { measured ->
                val placeable = measured.placeable
                val y = Alignment.CenterVertically.align(placeable.height, boxHeight)
                if (controlsOnRight) {
                    placeable.place(boxWidth - rightPlaced - placeable.width, y)
                    rightPlaced += placeable.width
                } else {
                    placeable.place(leftPlaced, y)
                    leftPlaced += placeable.width
                }
            }

            result.startMeasuredPlaceables.forEach { measured ->
                val placeable = measured.placeable
                val y = Alignment.CenterVertically.align(placeable.height, boxHeight)
                if (contentIsRtl) {
                    placeable.place(boxWidth - rightPlaced - placeable.width, y)
                    rightPlaced += placeable.width
                } else {
                    placeable.place(leftPlaced, y)
                    leftPlaced += placeable.width
                }
            }

            centerPlaceable?.let { placeable ->
                val y = Alignment.CenterVertically.align(placeable.height, boxHeight)
                placeable.place(centerLaneX, y)
            }
        }
    }
}

private data class FillCenterTitleBarMeasureResult(
    override val heightPx: Int,
    val startMeasuredPlaceables: List<MeasuredLayoutChild>,
    val endMeasuredPlaceables: List<MeasuredLayoutChild>,
    val startOccupied: Int,
    val endOccupied: Int,
    val centerMeasurable: Measurable?,
) : TitleBarMeasureResult
