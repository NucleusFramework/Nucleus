package dev.nucleusframework.window.tao

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.nucleusframework.window.styling.LocalDecoratedWindowStyle
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the card of a hovered tab gets to see: the tab, its workspace, and the
 * last picture taken of its body.
 */
public interface TabHoverPreviewScope {
    /** The workspace the tab belongs to. */
    public val workspace: TabWorkspace

    /** The group whose strip the pointer is over. */
    public val group: TabWindowGroup

    /** The tab under the pointer. */
    public val tab: TabEntry

    /**
     * The last picture taken of [tab]'s body, or `null` when there is none —
     * captures are off, or the tab has not been on screen yet. See
     * [TabEntry.thumbnail].
     */
    public val thumbnail: ImageBitmap? get() = tab.thumbnail
}

internal class TabHoverPreviewScopeImpl(
    override val workspace: TabWorkspace,
    override val group: TabWindowGroup,
    override val tab: TabEntry,
) : TabHoverPreviewScope

/**
 * How a strip previews the tab under the pointer: a browser's hover card,
 * shown under the tab after a pause and gone as soon as the pointer leaves it.
 *
 * Never for the selected tab, whose body is on screen anyway — see
 * [TabStripScope.hoveredTab] for every case a card is withheld.
 *
 * The whole card is [content], so an app draws its own — the title, the path,
 * a picture of the page, whatever it knows about the tab — and the stock
 * [TabHoverPreviewCard] is one composable it can build on or replace outright:
 *
 * ```kotlin
 * TabStrip(
 *     hoverPreview =
 *         TabHoverPreview(delay = 400.milliseconds) {
 *             TabHoverPreviewCard(subtitle = { Text(documents[tab.id]?.path.orEmpty()) })
 *         },
 * )
 * ```
 *
 * Pass it to [TabStrip], or compose [TabHoverPreviewPopup] with it in a strip
 * written from scratch.
 *
 * @property delay how long the pointer has to rest on a tab before the first
 *   card appears. Moving to another tab while one is shown switches at once,
 *   the way a browser does.
 * @property offset where the card sits relative to the tab's bottom-left
 *   corner — its bottom-*right* in a right-to-left strip, so the card grows
 *   into the reading direction on both.
 * @property nativeLayer whether the card is hosted on a native popup surface
 *   ([NativePopupLayers]), which is what lets it hang below the window like a
 *   browser's. `false` draws it inside the window's own scene, where it is
 *   kept within the window's bounds and clipped by them.
 * @property content the card. Composed with the hovered tab as receiver.
 */
@Immutable
public class TabHoverPreview(
    public val delay: Duration = HoverPreviewDelay,
    public val offset: DpOffset = HoverPreviewOffset,
    public val nativeLayer: Boolean = true,
    public val content: @Composable TabHoverPreviewScope.() -> Unit = { TabHoverPreviewCard() },
) {
    /** The stock hover card, for a strip that wants a browser's behaviour and nothing else. */
    public companion object {
        /** [TabHoverPreview] with every default: the stock card, after the stock pause. */
        public val Default: TabHoverPreview = TabHoverPreview()
    }
}

/**
 * The tab the pointer is resting on in this strip, which is what a hover card
 * follows.
 *
 * `null` in every case where a card would be wrong:
 *
 *  - the pointer is over no tab of this strip;
 *  - the tab under it is the *selected* one — its body is on screen already,
 *    and a card of what is being read is nothing but in the way;
 *  - a tab of the workspace is being dragged, which passes it over every
 *    neighbour in turn without pointing at any of them;
 *  - a press is in flight on the hovered tab, until the pointer has moved on.
 *
 * Published by [Modifier.tabSlot], so a strip written from scratch has it as
 * soon as it marks its slots.
 */
public val TabStripScope.hoveredTab: TabEntry?
    get() {
        if (workspace.draggedTab != null || group.hoverBlocked) return null
        val id = group.hoveredId ?: return null
        if (id == group.selectedId) return null
        if (id !in group.ids) return null
        return workspace.tab(id)
    }

/**
 * The hover card of this strip: [preview]'s content under the tab the pointer
 * rests on, at the place [Modifier.tabSlot] published for it.
 *
 * [TabStrip] composes it for its `hoverPreview`; a strip written from scratch
 * composes it once, next to its tabs, and needs nothing else — the tab is
 * [hoveredTab] and the anchor is the slot the strip already marks.
 *
 * The card is never a hover target itself: reaching it with the pointer puts
 * it away, since reaching it means having left the tab.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
public fun TabStripScope.TabHoverPreviewPopup(preview: TabHoverPreview = TabHoverPreview.Default) {
    val candidate = hoveredTab
    // The card waits out `delay` on the first tab and then follows the pointer
    // from tab to tab without a pause, as a browser's does.
    var shown by remember(group) { mutableStateOf<TabEntry?>(null) }
    LaunchedEffect(candidate, preview.delay) {
        if (candidate == null) {
            shown = null
            return@LaunchedEffect
        }
        if (shown == null) delay(preview.delay)
        shown = candidate
    }

    val tab = shown ?: return
    // Read off the settled layout the strip publishes, re-read when the strip
    // order changes: the slots are written from layout and are not snapshot
    // state, so `ids` is what says the anchor may have moved.
    val order = group.ids
    val density = LocalDensity.current
    val position =
        remember(tab, order, preview.offset, density) {
            val slot = group.slotInWindowPx(tab.id) ?: return@remember null
            TabHoverPreviewPosition(
                anchorPx = slot,
                offsetPx =
                    with(density) {
                        IntOffset(preview.offset.x.roundToPx(), preview.offset.y.roundToPx())
                    },
            )
        } ?: return
    val scope = remember(workspace, group, tab) { TabHoverPreviewScopeImpl(workspace, group, tab) }

    val card =
        @Composable {
            Popup(
                popupPositionProvider = position,
                properties =
                    PopupProperties(
                        // Never takes focus and never eats a pointer event:
                        // the card appears while the strip is being used, and
                        // the click that follows belongs to the tab.
                        focusable = false,
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                        // On a native surface the card may hang below the
                        // window, which is where a browser's sits; drawn
                        // in-scene it has to stay inside the window or it is
                        // cut off at its edge.
                        clippingEnabled = !preview.nativeLayer,
                    ),
            ) {
                // The card is no target of its own: the moment the pointer
                // reaches it, the tab it belongs to has been left behind, and
                // a browser's card goes away. It has to be said here — a popup
                // surface takes the pointer off the window beneath it, so the
                // tab never hears the pointer leave and the card would sit
                // over the content it covers until something else moved.
                Box(Modifier.onPointerEvent(PointerEventType.Enter) { group.noteHoverExit(tab.id) }) {
                    preview.content(scope)
                }
            }
        }
    if (preview.nativeLayer) NativePopupLayers { card() } else card()
}

/**
 * Where a hover card goes: under the tab it belongs to.
 *
 * The anchor is the tab's own slot in window pixels — the rect
 * [Modifier.tabSlot] publishes — and not the `anchorBounds` handed in, which
 * is the strip's whole width: the card is composed once for the strip, not per
 * tab, so the tab it points at is the one the strip picked.
 */
internal class TabHoverPreviewPosition(
    private val anchorPx: Rect,
    private val offsetPx: IntOffset,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // The card grows into the reading direction: from the tab's leading
        // edge, which is its right in a right-to-left strip.
        val x =
            if (layoutDirection == LayoutDirection.Rtl) {
                anchorPx.right.roundToInt() - popupContentSize.width - offsetPx.x
            } else {
                anchorPx.left.roundToInt() + offsetPx.x
            }
        val y = anchorPx.bottom.roundToInt() + offsetPx.y
        // Kept within the window across the strip: a card that runs past the
        // last tab would otherwise hang off the side of the window.
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(x.coerceIn(0, maxX), y)
    }
}

/**
 * The stock hover card: the tab's full title, whatever [subtitle] adds under
 * it, and the last picture taken of the tab's body when there is one
 * ([TabHoverPreviewScope.thumbnail]).
 *
 * Colours come from the window and title-bar styles, so the card matches the
 * chrome the app installed. Anything else is the app's own card —
 * [TabHoverPreview] takes it whole.
 *
 * @param modifier applied to the card itself, which is where a fixed width or
 *   a different padding goes.
 * @param subtitle a second line under the title: the path of a file, the host
 *   of a page. Nothing by default, since the workspace knows only the title.
 */
@Composable
public fun TabHoverPreviewScope.TabHoverPreviewCard(
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
) {
    val titleColors = LocalTitleBarStyle.current.colors
    val background = LocalDecoratedWindowStyle.current.colors.background
    val shape = RoundedCornerShape(HoverCardCornerRadius)
    Column(
        modifier =
            modifier
                .widthIn(min = HoverCardMinWidth, max = HoverCardMaxWidth)
                .background(background, shape)
                .border(HoverCardBorderWidth, titleColors.border, shape)
                .padding(HoverCardPadding),
    ) {
        BasicText(
            text = tab.title,
            style =
                TextStyle(
                    color = titleColors.content,
                    fontSize = HOVER_CARD_TITLE_SP.sp,
                    fontWeight = FontWeight.Medium,
                ),
            maxLines = HOVER_CARD_TITLE_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(HoverCardGap))
            subtitle()
        }
        thumbnail?.let { picture ->
            Spacer(Modifier.height(HoverCardGap))
            Image(
                bitmap = picture,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(picture.width.toFloat() / picture.height.toFloat())
                        .clip(RoundedCornerShape(HoverCardPictureRadius)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * Records the tab's body into a layer of its own and keeps a reduced picture
 * of it on the entry, which is what a hover card of a tab that is not the
 * selected one has to draw.
 *
 * Composed by [TabWindows] around the selected tab's body, and only for a
 * workspace built with `captureThumbnails` — it sits *above* the relocation
 * anchor, so the path from that anchor down to the content is the same in
 * every window and `rememberSaveable` state still follows a tab across.
 */
@Suppress("FunctionNaming")
@Composable
internal fun TabThumbnailRecorder(
    tab: TabEntry,
    content: @Composable () -> Unit,
) {
    val recorded = rememberGraphicsLayer()
    val reduced = rememberGraphicsLayer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    Box(
        modifier =
            Modifier.fillMaxSize().drawWithContent {
                recorded.record { this@drawWithContent.drawContent() }
                drawLayer(recorded)
            },
    ) {
        content()
    }
    LaunchedEffect(tab, recorded, reduced, density, layoutDirection) {
        snapshotFlow { tab.thumbnailRequest }.collectLatest {
            // The body has to have drawn once for the layer to hold anything,
            // and a picture taken the frame a tab arrives catches it mid
            // animation: one settle, then the readback. `collectLatest`
            // collapses a burst of requests into the last one.
            delay(ThumbnailSettleMillis)
            reducedPicture(recorded, reduced, density, layoutDirection)?.let { tab.thumbnail = it }
        }
    }
}

/**
 * [source] drawn into [into] at a size no larger than [THUMBNAIL_MAX_SIDE_PX]
 * on its longest side, and read back.
 *
 * Reduced rather than read back whole: a hover card is a couple of hundred dp
 * across, and keeping a window-sized bitmap per tab would cost megabytes for
 * something that is never drawn at that size.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun reducedPicture(
    source: GraphicsLayer,
    into: GraphicsLayer,
    density: Density,
    layoutDirection: LayoutDirection,
): ImageBitmap? {
    val size = source.size
    if (size.width <= 0 || size.height <= 0) return null
    val factor = (THUMBNAIL_MAX_SIDE_PX.toFloat() / max(size.width, size.height)).coerceAtMost(1f)
    val target =
        IntSize(
            (size.width * factor).roundToInt().coerceAtLeast(1),
            (size.height * factor).roundToInt().coerceAtLeast(1),
        )
    // A picture is cosmetic: a readback that fails must leave the last one in
    // place, never take the window with it.
    return try {
        into.record(density, layoutDirection, target) {
            scale(factor, factor, Offset.Zero) { drawLayer(source) }
        }
        into.toImageBitmap()
    } catch (error: Exception) {
        thumbnailLogger.log(Level.FINE, "tab thumbnail readback failed", error)
        null
    }
}

private val thumbnailLogger: Logger = Logger.getLogger("dev.nucleusframework.window.tao.tabthumbnail")

/** How long a body is given to draw and settle before its picture is taken. */
private val ThumbnailSettleMillis: Duration = THUMBNAIL_SETTLE_MILLIS.milliseconds

private val HoverPreviewDelay: Duration = HOVER_PREVIEW_DELAY_MILLIS.milliseconds
private val HoverPreviewOffset: DpOffset = DpOffset(0.dp, 4.dp)
private val HoverCardMinWidth: Dp = 160.dp
private val HoverCardMaxWidth: Dp = 280.dp
private val HoverCardPadding: Dp = 10.dp
private val HoverCardGap: Dp = 6.dp
private val HoverCardCornerRadius: Dp = 8.dp
private val HoverCardPictureRadius: Dp = 4.dp
private val HoverCardBorderWidth: Dp = 1.dp
private const val HOVER_PREVIEW_DELAY_MILLIS = 650
private const val HOVER_CARD_TITLE_SP = 12
private const val HOVER_CARD_TITLE_LINES = 2
private const val THUMBNAIL_SETTLE_MILLIS = 400
private const val THUMBNAIL_MAX_SIDE_PX = 512
