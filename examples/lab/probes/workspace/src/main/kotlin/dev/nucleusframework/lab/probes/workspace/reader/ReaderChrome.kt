package dev.nucleusframework.lab.probes.workspace.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.Divider
import dev.nucleusframework.lab.designsystem.LabShapes
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.window.tao.DockSplitterScope
import dev.nucleusframework.window.tao.SatelliteScope
import dev.nucleusframework.window.tao.satelliteDragHandle

/**
 * The reader's own pane header: a 32 dp strip, bold title, actions on hover. Docked, the
 * whole strip is the grip that drags the pane; floating, the title bar already is.
 */
@Composable
fun SatelliteScope.PaneHeader(style: ReaderStyle) {
    val colors = LabTheme.colors
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val background = if (style == ReaderStyle.Islands) colors.raised.copy(alpha = 0.15f) else colors.panel
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (isDocked) background else Color.Transparent)
            .hoverable(hover)
            .then(if (isDocked) Modifier.satelliteDragHandle(this) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(satellite.title, style = LabTheme.typography.heading)
            AnimatedVisibility(visible = hovered, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isDocked) {
                        // A fixed pane has nowhere to float to.
                        if (satellite.isFloatable) HeaderAction("↗") { undock() }
                    } else {
                        HeaderAction("↙") { dock() }
                    }
                    HeaderAction("—") { close() }
                }
            }
        }
        if (isDocked && style == ReaderStyle.Classic) Divider()
    }
}

/**
 * Drawn only where the compositor owns the window move (native Wayland): tells "press here
 * to move the window" apart from the header beside it, which drags the pane into the dock.
 */
@Composable
fun SatelliteScope.PaneMoveAffordance() {
    Text("✥", style = LabTheme.typography.small, color = LabTheme.colors.textMuted.copy(alpha = 0.55f))
}

@Composable
private fun HeaderAction(
    glyph: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(24.dp).clip(LabShapes.small).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = LabTheme.typography.small, color = LabTheme.colors.textMuted)
    }
}

/** A 1 dp line (invisible in Islands) carrying a 5 dp invisible grip — the split pane's visible part and handle. */
@Composable
fun DockSplitterScope.ReaderSplitter(style: ReaderStyle) {
    val horizontal = orientation == Orientation.Horizontal
    val line = if (horizontal) Modifier.fillMaxHeight().width(1.dp) else Modifier.fillMaxWidth().height(1.dp)
    val color = if (style == ReaderStyle.Islands) Color.Transparent else LabTheme.colors.border
    Box(line.background(color), contentAlignment = Alignment.Center) {
        val grip =
            if (horizontal) {
                Modifier
                    .requiredWidth(
                        5.dp,
                    ).fillMaxHeight()
            } else {
                Modifier.requiredHeight(5.dp).fillMaxWidth()
            }
        Box(grip.dockSplitterHandle())
    }
}

/** Nothing in Classic, where panes butt along the dividers; a rounded card in Islands. */
@Composable
fun PaneCard(
    style: ReaderStyle,
    content: @Composable () -> Unit,
) {
    val surface = LabTheme.colors.background
    val modifier =
        if (style == ReaderStyle.Islands) {
            Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(surface)
        } else {
            Modifier.fillMaxSize().background(surface)
        }
    Box(modifier) { content() }
}
