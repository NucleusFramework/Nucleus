package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import java.util.concurrent.ConcurrentHashMap

internal const val DEFAULT_WINDOW_WIDTH_DP = 800.0
internal const val DEFAULT_WINDOW_HEIGHT_DP = 600.0

private val sizePolicies = ConcurrentHashMap<Long, WindowSizePolicy>()

internal fun TaoWindow.installSizePolicy(policy: WindowSizePolicy) {
    sizePolicies[handle] = policy
}

internal fun TaoWindow.clearSizePolicy() {
    sizePolicies.remove(handle)
}

internal fun TaoWindow.resolvedSizePolicy(): WindowSizePolicy = sizePolicies[handle] ?: WindowSizePolicy()

/**
 * Wrap-content axes for a [DecoratedWindow] whose [androidx.compose.ui.window.WindowState.size]
 * has [Dp.Unspecified] on one or both dimensions (#532).
 */
internal class WindowSizePolicy(
    val wrapWidth: Boolean = false,
    val wrapHeight: Boolean = false,
    val onContentMeasured: ((IntSize) -> Unit)? = null,
) {
    val wraps: Boolean get() = wrapWidth || wrapHeight
}

internal fun Dp.toWindowCreationDp(fallback: Double): Double =
    if (isSpecified && value.isFinite() && value > 0f) value.toDouble() else fallback

/**
 * Scene column: [fillMaxSize] when both axes are specified (the 99% path),
 * wrap-content modifiers + [onSizeChanged] when an axis is [Dp.Unspecified].
 *
 * Reads the policy installed on [LocalTaoWindow] so [openDecoratedWindow]
 * does not grow another parameter.
 */
@Composable
internal fun WindowSceneColumn(content: @Composable ColumnScope.() -> Unit) {
    val policy = LocalTaoWindow.current?.resolvedSizePolicy() ?: WindowSizePolicy()
    val modifier =
        if (!policy.wraps) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .then(if (policy.wrapWidth) Modifier.wrapContentWidth(unbounded = true) else Modifier.fillMaxWidth())
                .then(if (policy.wrapHeight) Modifier.wrapContentHeight(unbounded = true) else Modifier.fillMaxHeight())
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0) policy.onContentMeasured?.invoke(size)
                }
        }
    Column(modifier = modifier, content = content)
}

/**
 * Builds the first real [DpSize] for a wrap-content window from the
 * content's measured pixels. Specified axes keep the requested value;
 * unspecified axes take the measured size (floored by [minimumSize]).
 * Returns `null` when the wrap axes have not produced a positive size yet.
 */
internal fun resolveWrapContentSize(
    wrapWidth: Boolean,
    wrapHeight: Boolean,
    requested: DpSize,
    minimumSize: DpSize?,
    measured: IntSize,
    scale: Float,
): DpSize? {
    if (scale <= 0f) return null
    val measuredW = (measured.width / scale).dp
    val measuredH = (measured.height / scale).dp
    val width =
        when {
            !wrapWidth && requested.width.isSpecified -> requested.width
            measured.width > 0 -> measuredW
            else -> return null
        }
    val height =
        when {
            !wrapHeight && requested.height.isSpecified -> requested.height
            measured.height > 0 -> measuredH
            else -> return null
        }
    val minW = minimumSize?.width
    val minH = minimumSize?.height
    val flooredW = if (minW != null && minW.isSpecified && width < minW) minW else width
    val flooredH = if (minH != null && minH.isSpecified && height < minH) minH else height
    if (flooredW.value <= 0f || flooredH.value <= 0f) return null
    return DpSize(flooredW, flooredH)
}
