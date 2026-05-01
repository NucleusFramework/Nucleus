package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import kotlin.math.max
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

// Native traffic-lights occupy roughly the leftmost 78 points on macOS. On
// Windows the title bar zone is fully under Compose's control — no native
// reservation, the user's TitleBar content places min/max/close itself.
private val NATIVE_BUTTONS_INSET_MACOS: Dp = 78.dp
private val NATIVE_BUTTONS_INSET_NONE: Dp = 0.dp

/**
 * Scope passed to [TitleBar]'s content lambda. Mirrors `decorated-window-core`'s
 * `TitleBarScope`: place children with `Modifier.align(Alignment.Start)`,
 * `.End` or `.CenterHorizontally`. The default alignment is center.
 */
@Stable
interface TitleBarScope {
    val title: String

    fun Modifier.align(alignment: Alignment.Horizontal): Modifier
}

@Suppress("FunctionNaming")
@Composable
fun DecoratedWindowScope.TitleBar(
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    background: Color = Color.Transparent,
    titleColor: Color = Color(0xFFE6E6E6),
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = { state ->
        BasicText(
            text = title,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = TextStyle(
                color = if (state.isActive) titleColor else titleColor.copy(alpha = 0.5f),
                fontSize = 12.sp,
            ),
        )
    },
) {
    val taoWindow = window
    val titleText = LocalDecoratedWindowTitle.current
    val currentState = state
    val scope = remember(titleText) { TitleBarScopeImpl(title = titleText) }

    // Publish our requested height up to DecoratedWindow, which applies the
    // native button-centering constraints once the window is shown (post
    // first Resized event — earlier the AppKit title-bar hierarchy isn't
    // ready yet and applying constraints during warm-up corrupts it).
    val heightHolder = LocalRequestedTitleBarHeight.current
    SideEffect { heightHolder.value = height.value }

    val (leftInset, rightInset) = when (Platform.Current) {
        Platform.MacOS -> NATIVE_BUTTONS_INSET_MACOS to NATIVE_BUTTONS_INSET_MACOS
        else -> NATIVE_BUTTONS_INSET_NONE to NATIVE_BUTTONS_INSET_NONE
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(background)
            .windowDragHandler(taoWindow),
    ) {
        Layout(
            content = {
                scope.content(currentState)
                // Windows: native min/max/close are not painted by DWM (the
                // WndProc subclass returns HTCLIENT for the title bar zone),
                // so the library injects its own Compose buttons here. The
                // user does not see them on macOS, where AppKit traffic-light
                // buttons are positioned by `nativeApplyButtonLayout`.
                if (Platform.Current == Platform.Windows) {
                    with(scope) {
                        WindowControlsWindows(
                            win = taoWindow,
                            state = currentState,
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(height),
            measurePolicy = remember(leftInset, rightInset) { TitleBarMeasurePolicy(leftInset, rightInset) },
        )
    }
}

internal class TitleBarScopeImpl(
    override val title: String,
) : TitleBarScope {
    override fun Modifier.align(alignment: Alignment.Horizontal): Modifier =
        this then TitleBarChildDataElement(alignment)
}

// ── Layout ────────────────────────────────────────────────────────────────

private class TitleBarMeasurePolicy(
    private val leftInset: Dp,
    private val rightInset: Dp,
) : androidx.compose.ui.layout.MeasurePolicy {
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun androidx.compose.ui.layout.MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: androidx.compose.ui.unit.Constraints,
    ): androidx.compose.ui.layout.MeasureResult {
        val leftPx = leftInset.roundToPx()
        val rightPx = rightInset.roundToPx()
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)

        val starts = mutableListOf<Placeable>()
        val ends = mutableListOf<Placeable>()
        val centers = mutableListOf<Placeable>()
        var maxH = constraints.minHeight

        // End first — they get priority on the right edge.
        var endTotal = 0
        for (m in measurables) {
            val align = (m.parentData as? TitleBarChildDataNode)?.horizontalAlignment
            if (align != Alignment.End) continue
            val p = m.measure(childConstraints.offset(horizontal = -endTotal - leftPx - rightPx))
            endTotal += p.width
            maxH = max(maxH, p.height)
            ends += p
        }

        // Start.
        var startTotal = 0
        for (m in measurables) {
            val align = (m.parentData as? TitleBarChildDataNode)?.horizontalAlignment
            if (align != Alignment.Start) continue
            val p = m.measure(childConstraints.offset(horizontal = -startTotal - leftPx - rightPx - endTotal))
            startTotal += p.width
            maxH = max(maxH, p.height)
            starts += p
        }

        // Center (default).
        var centerTotal = 0
        for (m in measurables) {
            val align = (m.parentData as? TitleBarChildDataNode)?.horizontalAlignment
            if (align != null && align != Alignment.CenterHorizontally) continue
            val p = m.measure(childConstraints)
            centerTotal += p.width
            maxH = max(maxH, p.height)
            centers += p
        }

        val width = constraints.maxWidth
        val height = max(maxH, constraints.minHeight)

        return layout(width, height) {
            val y: (Int) -> Int = { ph -> (height - ph) / 2 }

            var sx = leftPx
            starts.forEach { p ->
                p.place(sx, y(p.height))
                sx += p.width
            }

            var ex = width - rightPx
            ends.reversed().forEach { p ->
                ex -= p.width
                p.place(ex, y(p.height))
            }

            val centerStartLimit = leftPx + startTotal
            val centerEndLimit = width - rightPx - endTotal - centerTotal
            var cx = (width - centerTotal) / 2
            if (centerStartLimit <= centerEndLimit) {
                cx = cx.coerceIn(centerStartLimit, centerEndLimit)
                centers.forEach { p ->
                    p.place(cx, y(p.height))
                    cx += p.width
                }
            }
        }
    }
}

private class TitleBarChildDataElement(
    val horizontalAlignment: Alignment.Horizontal,
) : ModifierNodeElement<TitleBarChildDataNode>() {
    override fun create(): TitleBarChildDataNode = TitleBarChildDataNode(horizontalAlignment)

    override fun update(node: TitleBarChildDataNode) {
        node.horizontalAlignment = horizontalAlignment
    }

    override fun equals(other: Any?): Boolean =
        other is TitleBarChildDataElement && other.horizontalAlignment == horizontalAlignment

    override fun hashCode(): Int = horizontalAlignment.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "align"
        value = horizontalAlignment
    }
}

private class TitleBarChildDataNode(
    var horizontalAlignment: Alignment.Horizontal,
) : Modifier.Node(),
    ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?) = this@TitleBarChildDataNode
}

// ── Drag ──────────────────────────────────────────────────────────────────

private fun Modifier.windowDragHandler(window: TaoWindow): Modifier =
    pointerInput(window) {
        val ctx = currentCoroutineContext()
        awaitPointerEventScope {
            while (ctx.isActive) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: continue
                if (event.type == PointerEventType.Press && !change.isConsumed) {
                    change.consume()
                    window.dragWindow()
                }
            }
        }
    }
