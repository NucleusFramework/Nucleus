package dev.nucleusframework.readerdockdemo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.window.tao.DockSplitterScope
import dev.nucleusframework.window.tao.SatelliteScope
import dev.nucleusframework.window.tao.satelliteDragHandle

/**
 * The reader's pane header: a 32 dp strip with the bold title and, on hover,
 * the pane's actions — float or dock, and hide. The whole strip is the grip
 * that drags the pane between its dock and its own window.
 *
 * Composed by the satellite in both hosts: above the panel in the dock and in
 * the title bar of the floating window, where the bar already is the grip.
 */
@Composable
fun SatelliteScope.PaneHeader(style: ReaderStyle) {
    val colors = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val background =
        if (style ==
            ReaderStyle.Islands
        ) {
            colors.surfaceContainerHigh.copy(alpha = ISLANDS_HEADER_ALPHA)
        } else {
            colors.surfaceContainer
        }
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (isDocked) background else Color.Transparent)
            .hoverable(hover)
            .then(if (isDocked) Modifier.satelliteDragHandle(this) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().height(HEADER_HEIGHT_DP.dp).padding(horizontal = HEADER_PADDING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(satellite.title, fontWeight = FontWeight.Bold, fontSize = HEADER_TEXT_SP.sp, color = colors.onSurface)
            AnimatedVisibility(visible = hovered, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ACTION_GAP_DP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isDocked) {
                        // A fixed pane has nowhere to float to.
                        if (satellite.isFloatable) HeaderAction(FLOAT_GLYPH) { undock() }
                    } else {
                        HeaderAction(DOCK_GLYPH) { dock() }
                    }
                    HeaderAction(HIDE_GLYPH) { close() }
                }
            }
        }
        if (isDocked && style == ReaderStyle.Classic) HorizontalDivider()
    }
}

/**
 * What the floating pane draws in the strip its title bar leaves to the
 * compositor: the grip that says "press here to move the window", as opposed
 * to the header beside it, which drags the pane into the dock.
 *
 * Composed only where the two gestures have to be told apart
 * ([SatelliteScope.isCompositorPlaced]); the slot is not composed at all
 * elsewhere, so this costs nothing on Windows, macOS and X11.
 */
@Composable
fun SatelliteScope.PaneMoveAffordance() {
    Text(
        MOVE_GLYPH,
        fontSize = ACTION_GLYPH_SP.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MOVE_GLYPH_ALPHA),
    )
}

@Composable
private fun HeaderAction(
    glyph: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(ACTION_SIZE_DP.dp)) {
        Text(glyph, fontSize = ACTION_GLYPH_SP.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The reader's splitter: a 1 dp divider — invisible in the Islands style, where
 * the cards' gaps are the dividers — carrying a wider invisible grip, exactly
 * the split pane's `visiblePart` and `handle`.
 */
@Composable
fun DockSplitterScope.ReaderSplitter(style: ReaderStyle) {
    val horizontal = orientation == Orientation.Horizontal
    val line =
        if (horizontal) {
            Modifier.fillMaxHeight().width(
                DIVIDER_DP.dp,
            )
        } else {
            Modifier.fillMaxWidth().height(DIVIDER_DP.dp)
        }
    val color = if (style == ReaderStyle.Islands) Color.Transparent else MaterialTheme.colorScheme.outlineVariant
    Box(line.background(color), contentAlignment = Alignment.Center) {
        val grip =
            if (horizontal) {
                Modifier
                    .requiredWidth(
                        GRIP_DP.dp,
                    ).fillMaxHeight()
            } else {
                Modifier.requiredHeight(GRIP_DP.dp).fillMaxWidth()
            }
        Box(grip.dockSplitterHandle())
    }
}

/**
 * The frame around a docked pane: nothing in the Classic style, where panes
 * butt against each other along the dividers; a rounded card in the Islands
 * style.
 */
@Composable
fun PaneCard(
    style: ReaderStyle,
    content: @Composable () -> Unit,
) {
    if (style == ReaderStyle.Islands) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    top = CARD_GAP_V_DP.dp,
                    bottom = CARD_GAP_V_DP.dp,
                    start = CARD_GAP_H_DP.dp,
                    end = CARD_GAP_H_DP.dp,
                ).clip(RoundedCornerShape(CARD_CORNER_DP.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) { content() }
    } else {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

@Composable
fun HorizontalDivider() {
    Box(Modifier.fillMaxWidth().height(DIVIDER_DP.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
fun VerticalDivider() {
    Box(Modifier.fillMaxHeight().width(DIVIDER_DP.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

private const val HEADER_HEIGHT_DP = 32
private const val HEADER_PADDING_DP = 8
private const val HEADER_TEXT_SP = 14
private const val ACTION_GAP_DP = 4
private const val ACTION_SIZE_DP = 24
private const val MOVE_GLYPH_ALPHA = 0.55f
private const val ACTION_GLYPH_SP = 12
private const val FLOAT_GLYPH = "\u2197"
private const val DOCK_GLYPH = "\u2199"
private const val HIDE_GLYPH = "\u2014"
private const val MOVE_GLYPH = "✥"
private const val ISLANDS_HEADER_ALPHA = 0.15f
private const val DIVIDER_DP = 1
private const val GRIP_DP = 5
private const val CARD_GAP_V_DP = 6
private const val CARD_GAP_H_DP = 4
private const val CARD_CORNER_DP = 12
