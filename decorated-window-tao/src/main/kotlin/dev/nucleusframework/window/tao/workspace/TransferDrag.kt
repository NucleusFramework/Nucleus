package dev.nucleusframework.window.tao.workspace

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropSourceModifierNode
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.dnd.TaoPrivateTransfer
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import kotlin.math.roundToInt

/**
 * A cross-window drag carried by the platform's drag-and-drop session — the
 * path taken where the client cannot read or set window positions (native
 * Wayland, see [supportsScreenPlacement]).
 *
 * The roles are inverted with respect to [ScreenDrag]: the *source* learns
 * nothing about where the pointer is, and the *target* window — the one the
 * pointer is over, which the compositor tells about it in its own coordinates
 * — resolves the drop and records the outcome on the session. What the source
 * gets is [end], once the session is over, to act on that record: dock, move,
 * tear off, or nothing.
 *
 * The compositor draws the drag icon; [title] on a card the size of
 * [ghostSizePx] is what it shows.
 */
internal interface TransferDrag {
    /** What the drag icon reads when it falls back to a title card. */
    val title: String

    /** The title card's size in physical pixels — the grabbed strip, not the grip. */
    val ghostSizePx: Size

    /**
     * What the drag icon pictures: the source window's whole content, one
     * region of it (a docked panel, a tab), or nothing but the title card.
     */
    val ghostSource: TransferGhostSource

    /** The session is over; act on what a target recorded, if any. */
    fun end()

    /** The session never started; publish nothing and change nothing. */
    fun cancel()
}

/** The part of the source window a transfer drag's icon is a picture of. */
internal sealed interface TransferGhostSource {
    /** The whole content area: a floating palette. */
    data object WholeWindow : TransferGhostSource

    /** One region, in the source window's content pixels: a docked panel, a tab. */
    data class Region(
        val rectPx: IntRect,
    ) : TransferGhostSource

    /** No picture; the title card stands in. */
    data object None : TransferGhostSource
}

/**
 * Makes this element the grip of a [TransferDrag]: a press that passes the
 * touch slop asks [begin] for the session and hands it to the platform's DnD
 * machinery, which owns the pointer until the release.
 *
 * Claims the press in the Main pass, exactly like [screenDragHandle], so the
 * title bar's compositor move does not start on the first sub-slop movement.
 * Compose's own `dragAndDropSource` leaves the press unclaimed, which is why
 * this builds on the same public [DragAndDropSourceModifierNode] rather than
 * on the finished modifier.
 */
@Composable
internal fun Modifier.transferDragHandle(
    key: Any?,
    window: TaoWindow,
    begin: () -> TransferDrag?,
): Modifier {
    val accent = LocalTitleBarStyle.current.colors.content
    val measurer = rememberTextMeasurer()
    val grab = remember { GrabCoordinates() }
    return this
        .onGloballyPositioned { grab.coordinates = it }
        .then(TransferDragElement(key, window, grab, begin, accent, measurer))
}

/**
 * Where the grip is, for turning the press into a window position.
 *
 * Read off a plain holder written by [Modifier.onGloballyPositioned] rather
 * than by making the drag node itself layout-aware: a `DelegatingNode` that
 * implements [androidx.compose.ui.node.LayoutAwareModifierNode] takes those
 * callbacks *instead of* its delegates, and Compose's own drag-and-drop source
 * node needs its `onPlaced` to learn its size — without it the node measures
 * as empty and silently refuses every transfer request.
 */
private class GrabCoordinates {
    var coordinates: LayoutCoordinates? = null
}

private data class TransferDragElement(
    val key: Any?,
    val window: TaoWindow,
    val grab: GrabCoordinates,
    val begin: () -> TransferDrag?,
    val accent: Color,
    val measurer: TextMeasurer,
) : ModifierNodeElement<TransferDragNode>() {
    override fun create(): TransferDragNode = TransferDragNode(window, grab, begin, accent, measurer)

    override fun update(node: TransferDragNode) {
        node.window = window
        node.grab = grab
        node.begin = begin
        node.accent = accent
        node.measurer = measurer
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "transferDragHandle"
        properties["key"] = key
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private class TransferDragNode(
    var window: TaoWindow,
    var grab: GrabCoordinates,
    var begin: () -> TransferDrag?,
    var accent: Color,
    var measurer: TextMeasurer,
) : DelegatingNode() {
    private val source =
        delegate(
            DragAndDropSourceModifierNode { offset ->
                val drag = begin() ?: return@DragAndDropSourceModifierNode
                val picture = snapshotFor(drag)
                val ghost = transferGhost(drag, picture)
                val grabInWindow = grab.coordinates?.takeIf { it.isAttached }?.localToWindow(offset)
                val started =
                    startDragAndDropTransfer(
                        transferData = transferDragData(drag, ghost.sizePx, ghost.hotspotPx(grabInWindow)),
                        decorationSize = ghost.sizePx,
                        drawDragDecoration = { drawTransferGhost(drag.title, picture, accent, measurer) },
                    )
                if (!started) drag.cancel()
            },
        )

    private fun snapshotFor(drag: TransferDrag): ImageBitmap? =
        when (val src = drag.ghostSource) {
            TransferGhostSource.WholeWindow -> window.snapshotContent(null)
            is TransferGhostSource.Region -> window.snapshotContent(src.rectPx)
            TransferGhostSource.None -> null
        }

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    // The press position, not the post-slop one: Compose only
                    // starts a transfer for a point inside the source node, and
                    // a grip is narrower than the slop.
                    if (source.isRequestDragAndDropTransferRequired) {
                        source.requestDragAndDropTransfer(down.position)
                    }
                }
            },
        )
    }
}

/** The token every transfer drag carries; the session's meaning lives in the workspace, not in the payload. */
internal const val TRANSFER_DRAG_TOKEN = "workspace-drag"

/**
 * The drag icon's geometry: its size, and how a point of the source window
 * maps into it. A picture is shown reduced — a palette-sized icon would hide
 * the very zones the drag is aimed at — and never larger than
 * [TRANSFER_GHOST_MAX_EDGE_PX] on its longer edge; the title card is shown as
 * it is.
 */
internal class TransferGhost(
    val sizePx: Size,
    /** Where the pictured region starts in the source window, content pixels. */
    val sourceTopLeftPx: Offset,
    /** Icon pixels per source pixel. */
    val scale: Float,
) {
    /**
     * The pointer's position inside the icon for a grab at [grabInWindowPx]
     * (source window content pixels), clamped to the icon. Without a grab
     * position the icon hangs from its top edge, centred on the pointer.
     */
    fun hotspotPx(grabInWindowPx: Offset?): Offset {
        val raw =
            if (grabInWindowPx == null) {
                Offset(sizePx.width / 2f, TRANSFER_GHOST_TOP_HOTSPOT_PX)
            } else {
                (grabInWindowPx - sourceTopLeftPx) * scale
            }
        return Offset(raw.x.coerceIn(0f, sizePx.width), raw.y.coerceIn(0f, sizePx.height))
    }
}

/** The icon [drag] gets: a reduced [picture] when there is one, else the title card. */
internal fun transferGhost(
    drag: TransferDrag,
    picture: ImageBitmap?,
): TransferGhost {
    val sourceTopLeft =
        (drag.ghostSource as? TransferGhostSource.Region)
            ?.rectPx
            ?.topLeft
            ?.let { Offset(it.x.toFloat(), it.y.toFloat()) } ?: Offset.Zero
    if (picture == null || picture.width <= 0 || picture.height <= 0) {
        return TransferGhost(drag.ghostSizePx, sourceTopLeft, scale = 1f)
    }
    val longest = maxOf(picture.width, picture.height).toFloat()
    val scale = minOf(TRANSFER_GHOST_SCALE, TRANSFER_GHOST_MAX_EDGE_PX / longest)
    return TransferGhost(Size(picture.width * scale, picture.height * scale), sourceTopLeft, scale)
}

/**
 * The transfer Compose hands to the platform for [drag]: an icon of
 * [ghostSizePx] with the pointer at [hotspotPx] inside it.
 *
 * Named rather than inlined at the call site because of
 * [DragAndDropTransferData.onTransferCompleted]: it is the *only* signal that
 * the platform session is over, and therefore the only thing that ends the
 * workspace's drag. Losing it strands the gesture — the drop record is never
 * acted on and the drop-zone highlights never clear — without any error, so it
 * is asserted on directly (`TransferDragTest`).
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun transferDragData(
    drag: TransferDrag,
    ghostSizePx: Size,
    hotspotPx: Offset,
): DragAndDropTransferData =
    DragAndDropTransferData(
        transferable = DragAndDropTransferable(TaoPrivateTransfer.transferable(TRANSFER_DRAG_TOKEN)),
        supportedActions = listOf(DragAndDropTransferAction.Move),
        // Compose places the icon's origin at the pointer plus this offset, so
        // the grab point stays under the pointer when it is minus the hotspot.
        dragDecorationOffset =
            -Offset(
                hotspotPx.x.coerceIn(0f, ghostSizePx.width),
                hotspotPx.y.coerceIn(0f, ghostSizePx.height),
            ),
        onTransferCompleted = { drag.end() },
    )

/**
 * What the compositor shows under the pointer: a reduced picture of the
 * dragged palette or panel when one could be taken, framed and slightly
 * translucent so the zones under it stay readable; else a card with the
 * title on a tinted, rounded surface. The drag-icon counterpart of the ghost
 * windows the screen-placing platforms fly.
 */
private fun DrawScope.drawTransferGhost(
    title: String,
    picture: ImageBitmap?,
    accent: Color,
    measurer: TextMeasurer,
) {
    val corner = CornerRadius(GHOST_CORNER_DP.dp.toPx())
    if (picture != null && picture.width > 0 && picture.height > 0) {
        val frame = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), corner)) }
        clipPath(frame) {
            drawImage(
                image = picture,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(picture.width, picture.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                alpha = GHOST_PICTURE_ALPHA,
            )
        }
        val stroke = GHOST_BORDER_DP.dp.toPx()
        drawRoundRect(
            color = accent.copy(alpha = GHOST_BORDER_ALPHA),
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = corner,
            style = Stroke(stroke),
        )
        return
    }
    drawRoundRect(color = accent.copy(alpha = GHOST_FILL_ALPHA), cornerRadius = corner)
    val stroke = GHOST_BORDER_DP.dp.toPx()
    drawRoundRect(
        color = accent.copy(alpha = GHOST_BORDER_ALPHA),
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = corner,
        style = Stroke(stroke),
    )
    val padding = GHOST_PADDING_DP.dp.toPx()
    val maxWidth = (size.width - padding * 2).roundToInt()
    if (maxWidth <= 0) return
    val layout =
        measurer.measure(
            text = AnnotatedString(title),
            style = TextStyle(color = accent, fontSize = GHOST_TITLE_SP.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = maxWidth),
        )
    drawText(layout, topLeft = Offset(padding, (size.height - layout.size.height) / 2f))
}

/** Icon pixels per source pixel for a pictured drag: readable, yet out of the way of the zones. */
private const val TRANSFER_GHOST_SCALE = 0.6f

/** Longest edge a pictured icon may have, whatever the source's size. */
private const val TRANSFER_GHOST_MAX_EDGE_PX = 480f

/** Where the pointer sits in an icon grabbed at an unknown position: just under the top edge. */
private const val TRANSFER_GHOST_TOP_HOTSPOT_PX = 12f

private const val GHOST_PICTURE_ALPHA = 0.92f
private const val GHOST_FILL_ALPHA = 0.22f
private const val GHOST_BORDER_ALPHA = 0.55f
private const val GHOST_BORDER_DP = 1
private const val GHOST_CORNER_DP = 8
private const val GHOST_PADDING_DP = 8
private const val GHOST_TITLE_SP = 13

/**
 * Where an inbound drag-and-drop event is, in the receiving window's content
 * coordinates (physical px) — the space the Tao hosts build their synthetic
 * AWT events in (see `TaoSceneDnD`). Compose keeps its own `positionInRoot`
 * internal, so the position is read back off the native event; `Unspecified`
 * for an event that is not one of the hosts', which no zone then contains.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun DragAndDropEvent.positionInWindowPx(): Offset =
    when (val native = nativeEvent) {
        is DropTargetDragEvent -> Offset(native.location.x.toFloat(), native.location.y.toFloat())
        is DropTargetDropEvent -> Offset(native.location.x.toFloat(), native.location.y.toFloat())
        else -> Offset.Unspecified
    }
