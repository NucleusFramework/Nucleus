package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMaxOfOrDefault

/**
 * How a [DecoratedWindow] resolves [androidx.compose.ui.window.WindowState.size]
 * when one or both axes are [Dp.Unspecified] (Compose Desktop wrap-content).
 */
internal class WindowSizePolicy(
    val wrapWidth: Boolean = false,
    val wrapHeight: Boolean = false,
    val onContentMeasured: ((widthPx: Int, heightPx: Int) -> Unit)? = null,
) {
    val wraps: Boolean get() = wrapWidth || wrapHeight
}

internal fun Dp.toWindowCreationDp(fallback: Double): Double =
    if (isSpecified && value.isFinite() && value > 0f) value.toDouble() else fallback

/**
 * Root of the window scene. Specified axes fill the window; wrap-content
 * axes measure children with an unbounded max so `fillMaxSize` chrome
 * outside this node cannot collapse [Dp.Unspecified] to the placeholder
 * creation size.
 */
@Composable
internal fun WindowContentRoot(
    policy: WindowSizePolicy,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!policy.wraps) {
        Layout(
            content = content,
            modifier = modifier.fillMaxWidth().fillMaxHeight(),
        ) { measurables, constraints ->
            val placeables = measurables.fastMap { it.measure(constraints) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.fastForEach { it.place(0, 0) }
            }
        }
        return
    }
    Layout(
        content = content,
        modifier =
            modifier.onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    policy.onContentMeasured?.invoke(size.width, size.height)
                }
            },
    ) { measurables, constraints ->
        val childConstraints =
            Constraints(
                minWidth = 0,
                maxWidth = if (policy.wrapWidth) Constraints.Infinity else constraints.maxWidth,
                minHeight = 0,
                maxHeight = if (policy.wrapHeight) Constraints.Infinity else constraints.maxHeight,
            )
        val placeables = measurables.fastMap { it.measure(childConstraints) }
        val width =
            if (policy.wrapWidth) {
                placeables.fastMaxOfOrDefault(0) { it.width }
            } else {
                constraints.maxWidth
            }
        val height =
            if (policy.wrapHeight) {
                placeables.fastMaxOfOrDefault(0) { it.height }
            } else {
                constraints.maxHeight
            }
        layout(width.coerceAtLeast(0), height.coerceAtLeast(0)) {
            placeables.fastForEach { it.place(0, 0) }
        }
    }
}
