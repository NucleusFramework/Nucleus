@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao.v2

import androidx.compose.runtime.Immutable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpInsets
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.plus
import androidx.compose.ui.unit.size
import kotlin.math.roundToInt

/**
 * The properties of a window that are useful inside a
 * [WindowGeometryProviderScope].
 *
 * AWT-free drop-in for `androidx.compose.ui.window.v2.WindowMetrics`. The
 * Compose original reads a live `java.awt.Window`; this one is a snapshot taken
 * when the provider is evaluated, which is the only moment a provider can
 * observe it anyway.
 */
@Immutable
public class WindowMetrics internal constructor(
    /** The screen on which the window is placed. */
    public val screen: Screen,
    /** The bounds of the entire window — decorations included — on the screen. */
    public val bounds: DpRect,
    /**
     * The window's insets: the areas where content isn't placed, such as the
     * title bar and resize borders.
     *
     * [DpInsets] of zero for the undecorated, client-side-decorated windows
     * `DecoratedWindow` draws by default, and while the native window has not
     * been measured yet.
     */
    public val insets: DpInsets,
) {
    /** The content area — [bounds] minus [insets]. */
    internal val contentSize: DpSize
        get() =
            DpSize(
                width = (bounds.size.width - insets.left - insets.right).coerceAtLeastZero(),
                height = (bounds.size.height - insets.top - insets.bottom).coerceAtLeastZero(),
            )
}

/**
 * The scope in which window geometry providers ([WindowBoundsProvider],
 * [WindowSizeProvider], [WindowPositionProvider]) are evaluated.
 *
 * AWT-free drop-in for `androidx.compose.ui.window.v2.WindowGeometryProviderScope`
 * — the class whose `java.awt.Window` constructor parameter makes every Compose
 * v2 geometry provider inert on the Tao backend.
 */
public class WindowGeometryProviderScope internal constructor(
    /** The window's metrics. */
    public val windowMetrics: WindowMetrics,
    /** The metrics of the parent window, if any. */
    public val parentWindowMetrics: WindowMetrics?,
    /** Scale the window's pixels are expressed in; converts measured px to dp. */
    private val scale: Float = 1f,
    /** The live scene's `measureContent`, or `null` before the window has one. */
    private val measureContent: ((Constraints) -> IntSize?)? = null,
) {
    /**
     * Returns the size a window should have, given the size of its content.
     *
     * The content size is expanded by the window's insets and then constrained
     * to [Screen.availableBounds].
     */
    public fun contentToWindowSize(contentSize: DpSize): DpSize =
        DpSize(
            width =
                (contentSize.width + windowMetrics.insets.left + windowMetrics.insets.right)
                    .coerceAtMostReal(windowMetrics.screen.availableBounds.size.width),
            height =
                (contentSize.height + windowMetrics.insets.top + windowMetrics.insets.bottom)
                    .coerceAtMostReal(windowMetrics.screen.availableBounds.size.height),
        )

    /**
     * Measures the window content in the given constraints and returns the
     * resulting size.
     *
     * A real measure pass against the live scene (`ComposeScene.measureContent`)
     * once the window has one. Before that — evaluating an *initial* provider,
     * or a host that never exposes its window — there is no content to measure,
     * so the current content size clamped to the constraints stands in.
     *
     * [maxWidth] and [maxHeight] can be [Dp.Infinity] to mean unconstrained.
     */
    public fun measureWindowContent(
        minWidth: Dp = 0.dp,
        maxWidth: Dp = Dp.Infinity,
        minHeight: Dp = 0.dp,
        maxHeight: Dp = Dp.Infinity,
    ): DpSize {
        val measured =
            measureContent?.invoke(
                Constraints(
                    minWidth = minWidth.toPxOrInfinity(),
                    maxWidth = maxWidth.toPxOrInfinity(),
                    minHeight = minHeight.toPxOrInfinity(),
                    maxHeight = maxHeight.toPxOrInfinity(),
                ),
            )
        if (measured != null) {
            return DpSize((measured.width / scale).dp, (measured.height / scale).dp)
        }
        val content = windowMetrics.contentSize
        return DpSize(
            width = content.width.clampTo(minWidth, maxWidth),
            height = content.height.clampTo(minHeight, maxHeight),
        )
    }

    private fun Dp.toPxOrInfinity(): Int = if (isReal) (value * scale).roundToInt() else Constraints.Infinity
}

/**
 * Evaluates [provider] in this scope.
 *
 * The scoped `getBounds` is a member extension — mirroring Compose, which keeps
 * provider evaluation out of its public API — so this is how the window bridge
 * reaches it.
 */
internal fun WindowGeometryProviderScope.evaluateBounds(provider: WindowBoundsProvider): DpRect =
    with(provider) { getBounds() }

/** Evaluates [provider] in this scope. See [evaluateBounds]. */
internal fun WindowGeometryProviderScope.evaluateSize(provider: WindowSizeProvider): DpSize =
    with(provider) { getSize() }

/** Evaluates [provider] in this scope. See [evaluateBounds]. */
internal fun WindowGeometryProviderScope.evaluatePosition(
    provider: WindowPositionProvider,
    size: DpSize,
): DpOffset = with(provider) { getPosition(size) }

/**
 * Clamps to `[min, max]`, tolerating the unspecified and infinite bounds the
 * geometry providers use to mean "no constraint".
 */
private fun Dp.clampTo(
    min: Dp,
    max: Dp,
): Dp {
    var result = this
    if (min.isReal && result < min) result = min
    if (max.isReal && result > max) result = max
    return result
}

private fun Dp.coerceAtMostReal(other: Dp): Dp = if (other.isReal && this > other) other else this

private fun Dp.coerceAtLeastZero(): Dp = if (value < 0f) 0.dp else this
