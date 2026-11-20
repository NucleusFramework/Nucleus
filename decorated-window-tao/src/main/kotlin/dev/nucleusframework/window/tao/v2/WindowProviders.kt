@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao.v2

import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.minus
import androidx.compose.ui.unit.size
import kotlin.math.roundToInt

internal val DEFAULT_WINDOW_SIZE: DpSize = DpSize(800.dp, 600.dp)

/**
 * Provides the bounds of the window.
 *
 * AWT-free drop-in for `androidx.compose.ui.window.v2.WindowBoundsProvider`.
 */
public interface WindowBoundsProvider {
    /**
     * Returns the bounds of the window.
     *
     * Use the [WindowGeometryProviderScope] receiver to examine the geometry of
     * the screen and the window.
     */
    public fun WindowGeometryProviderScope.getBounds(): DpRect

    /** Built-in providers. */
    public companion object {
        /** The default position and size for a new window. */
        public val Default: WindowBoundsProvider =
            WindowBoundsProvider(
                sizeProvider = WindowSizeProvider.Default,
                positionProvider = WindowPositionProvider.Default,
            )

        /**
         * Positions the window at the given [bounds].
         *
         * All coordinates must be specified and finite.
         */
        public fun Absolute(bounds: DpRect): WindowBoundsProvider {
            bounds.requireReal()
            return WindowBoundsProvider { bounds }
        }
    }
}

/** Creates a [WindowBoundsProvider] from the given [bounds] function. */
public fun WindowBoundsProvider(bounds: WindowGeometryProviderScope.() -> DpRect): WindowBoundsProvider =
    object : WindowBoundsProvider {
        override fun WindowGeometryProviderScope.getBounds(): DpRect = bounds()
    }

/** Combines a [WindowSizeProvider] and a [WindowPositionProvider]. */
public fun WindowBoundsProvider(
    sizeProvider: WindowSizeProvider = WindowSizeProvider.Current,
    positionProvider: WindowPositionProvider = WindowPositionProvider.Current,
): WindowBoundsProvider = CombinedBoundsProvider(sizeProvider, positionProvider)

/**
 * Size and position kept apart instead of folded into a [DpRect].
 *
 * A rectangle cannot carry the two sentinels this API relies on: an unspecified
 * position ([WindowPositionProvider.Default] — let the window manager choose) or
 * a wrap-content axis ([WindowSizeProvider.Unconstrained]) turns `right - left`
 * into `NaN`, taking the *other* value down with it. The bridge recognises this
 * type and evaluates the two providers separately; [getBounds] stays correct for
 * anything else that composes it.
 */
internal class CombinedBoundsProvider(
    val sizeProvider: WindowSizeProvider,
    val positionProvider: WindowPositionProvider,
) : WindowBoundsProvider {
    override fun WindowGeometryProviderScope.getBounds(): DpRect {
        val size = evaluateSize(sizeProvider)
        val position = evaluatePosition(positionProvider, size)
        val topLeft = if (position.isSpecified) position else windowMetrics.bounds.topLeft
        val resolved = if (size.isSpecified) size else windowMetrics.bounds.size
        return DpRect(topLeft, resolved)
    }
}

/**
 * Provides the position of the window.
 *
 * AWT-free drop-in for `androidx.compose.ui.window.v2.WindowPositionProvider`.
 */
public fun interface WindowPositionProvider {
    /**
     * Returns the position of the window, given the [size] it will have.
     *
     * Use the [WindowGeometryProviderScope] receiver to examine the geometry of
     * the screen and the parent window.
     */
    public fun WindowGeometryProviderScope.getPosition(size: DpSize): DpOffset

    /** Built-in providers. */
    public companion object {
        /**
         * Leaves the position to the window manager.
         *
         * Compose's original cascades new windows itself, through AWT's
         * `WindowLocationTracker`. On Tao the platform already does that — and
         * does it better on Wayland, where a client cannot position itself at
         * all — so this maps to
         * [androidx.compose.ui.window.WindowPosition.PlatformDefault], signalled
         * by an unspecified [DpOffset].
         */
        public val Default: WindowPositionProvider = WindowPositionProvider { DpOffset.Unspecified }

        /** Keeps the current position of the window. */
        public val Current: WindowPositionProvider = WindowPositionProvider { windowMetrics.bounds.topLeft }

        /** Centers the window within its screen. */
        public val CenteredOnScreen: WindowPositionProvider = AlignedToScreen(alignment = Alignment.Center)

        /** Centers the window within its parent window. */
        public val CenteredInParentWindow: WindowPositionProvider =
            AlignedToParentWindow(alignment = Alignment.Center, anchor = Alignment.Center)

        /** Positions the window at the given [position]. */
        public fun Absolute(position: DpOffset): WindowPositionProvider {
            position.requireReal()
            return WindowPositionProvider { position }
        }

        /** Positions the window at the given coordinates. */
        public fun Absolute(
            x: Dp,
            y: Dp,
        ): WindowPositionProvider = Absolute(DpOffset(x, y))

        /**
         * Aligns the window within its screen's available bounds according to
         * [alignment], then applies [offset].
         */
        public fun AlignedToScreen(
            alignment: Alignment,
            offset: DpOffset = DpOffset.Zero,
        ): WindowPositionProvider =
            WindowPositionProvider { size ->
                val availableBounds = windowMetrics.screen.availableBounds
                val position =
                    alignment.align(
                        size = size.roundToIntSize(),
                        space = availableBounds.size.roundToIntSize(),
                        layoutDirection = LayoutDirection.Ltr,
                    )
                DpOffset(
                    x = availableBounds.left + position.x.dp + offset.x,
                    y = availableBounds.top + position.y.dp + offset.y,
                )
            }

        /**
         * Aligns the window relative to its parent window.
         *
         * [anchor] is the point in the parent bounds the alignment is applied
         * around; [alignment] then places the window inside an area centred on
         * that point and twice the window's size, so
         * [Alignment.TopStart] puts the window's bottom-right corner on the
         * anchor. [excludeParentInsets] anchors against the parent's content
         * area instead of its whole frame.
         */
        public fun AlignedToParentWindow(
            anchor: Alignment,
            alignment: Alignment,
            offset: DpOffset = DpOffset.Zero,
            excludeParentInsets: Boolean = false,
        ): WindowPositionProvider =
            WindowPositionProvider { size ->
                val parentMetrics =
                    parentWindowMetrics
                        ?: error("No parent window metrics available; this window has no parent")
                val parentBounds =
                    if (excludeParentInsets) parentMetrics.bounds - parentMetrics.insets else parentMetrics.bounds

                val anchorInParent =
                    anchor.align(
                        size = IntSize.Zero,
                        space = parentBounds.size.roundToIntSize(),
                        layoutDirection = LayoutDirection.Ltr,
                    )
                val anchorPoint =
                    IntOffset(
                        anchorInParent.x + parentBounds.left.value.roundToInt(),
                        anchorInParent.y + parentBounds.top.value.roundToInt(),
                    )
                val intSize = size.roundToIntSize()
                val targetArea =
                    IntRect(
                        left = anchorPoint.x - intSize.width,
                        top = anchorPoint.y - intSize.height,
                        right = anchorPoint.x + intSize.width,
                        bottom = anchorPoint.y + intSize.height,
                    )
                val positionInTargetArea = alignment.align(intSize, targetArea.size, LayoutDirection.Ltr)
                DpOffset(
                    x = (targetArea.left + positionInTargetArea.x).dp,
                    y = (targetArea.top + positionInTargetArea.y).dp,
                ) + offset
            }
    }
}

/**
 * Provides the size of the window.
 *
 * AWT-free drop-in for `androidx.compose.ui.window.v2.WindowSizeProvider`.
 *
 * The wrap-content providers ([Unconstrained], [PreferredWidth],
 * [PreferredHeight]) return [Dp.Unspecified] on the axes the window should size
 * to its content. That is not a sentinel invented here: it is how
 * `DecoratedWindow` already expresses wrap-content, and it re-measures
 * continuously instead of freezing a one-shot measurement.
 */
public fun interface WindowSizeProvider {
    /**
     * Returns the size of the window.
     *
     * Use the [WindowGeometryProviderScope] receiver to examine the geometry of
     * the screen and the window's content.
     */
    public fun WindowGeometryProviderScope.getSize(): DpSize

    /** Built-in providers. */
    public companion object {
        /** The default size of a new window, 800×600. */
        public val Default: WindowSizeProvider = Fixed(DEFAULT_WINDOW_SIZE)

        /** Keeps the current size of the window. */
        public val Current: WindowSizeProvider = WindowSizeProvider { windowMetrics.bounds.size }

        /** Sets the size of the window to the given [size]. */
        public fun Fixed(size: DpSize): WindowSizeProvider {
            size.requireReal()
            return WindowSizeProvider { size }
        }

        /** Sets the size of the window to the given [width] and [height]. */
        public fun Fixed(
            width: Dp,
            height: Dp,
        ): WindowSizeProvider = Fixed(DpSize(width, height))

        /**
         * Sizes the window to its content on both axes, bounded by the screen's
         * available size.
         */
        public val Unconstrained: WindowSizeProvider = WindowSizeProvider { DpSize.Unspecified }

        /** Sizes the window to its content's preferred width at the given [height]. */
        public fun PreferredWidth(height: Dp): WindowSizeProvider {
            height.requireReal("height")
            return WindowSizeProvider { DpSize(Dp.Unspecified, height) }
        }

        /** Sizes the window to its content's preferred height at the given [width]. */
        public fun PreferredHeight(width: Dp): WindowSizeProvider {
            width.requireReal("width")
            return WindowSizeProvider { DpSize(width, Dp.Unspecified) }
        }
    }
}

// ── Internal geometry helpers ────────────────────────────────────────────────
// Compose keeps its equivalents internal to compose-ui, so they are re-declared
// here rather than reached into.

internal val DpRect.topLeft: DpOffset get() = DpOffset(left, top)

internal fun DpSize.roundToIntSize(): IntSize =
    IntSize(width = width.value.roundToInt(), height = height.value.roundToInt())

internal val Dp.isReal: Boolean get() = isSpecified && value.isFinite()

internal fun Dp.requireReal(name: String): Dp {
    require(isReal) { "$name must be specified and finite" }
    return this
}

internal fun DpSize.requireReal(): DpSize {
    require(isSpecified) { "size must be specified" }
    width.requireReal("width")
    height.requireReal("height")
    return this
}

internal fun DpOffset.requireReal(): DpOffset {
    require(isSpecified) { "offset must be specified" }
    x.requireReal("x")
    y.requireReal("y")
    return this
}

internal fun DpRect.requireReal(): DpRect {
    left.requireReal("left")
    top.requireReal("top")
    right.requireReal("right")
    bottom.requireReal("bottom")
    return this
}
