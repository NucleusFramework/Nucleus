package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Frames content under test (a Compose sample, a NativeView, a video, a texture) so its
 * bounds are visible and every specimen sits on the same neutral surface.
 */
@Composable
fun SpecimenFrame(
    modifier: Modifier = Modifier,
    height: Dp? = null,
    caption: String? = null,
    /** `false` wraps the specimen (fixed-size content) instead of filling the width. */
    fillWidth: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val width = if (fillWidth) Modifier.fillMaxWidth() else Modifier
    Column(modifier.then(width), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        caption?.let { Text(it, style = LabTheme.typography.small, color = LabTheme.colors.textMuted) }
        Box(
            Modifier
                .then(width)
                .then(if (height != null) Modifier.height(height) else Modifier)
                .clip(LabShapes.specimen)
                .background(LabSurfaces.specimen)
                .border(1.dp, LabTheme.colors.border, LabShapes.specimen),
            content = content,
        )
    }
}

/** A bordered area the tester acts on (scroll here, drop here, hover here), with its instruction. */
@Composable
fun TargetArea(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    label: String? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val colors = LabTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .clip(LabShapes.block)
            .background(if (highlighted) colors.selection else colors.target)
            .border(1.dp, if (highlighted) colors.accent else colors.borderStrong, LabShapes.block),
    ) {
        label?.let {
            Text(
                it,
                style = LabTheme.typography.label,
                color = if (highlighted) colors.text else colors.textMuted,
                modifier = Modifier.align(Alignment.Center).padding(LabDimens.block),
            )
        }
        content()
    }
}

/** A Compose caption drawn over native content: one ink and shape for all of them. */
@Composable
fun OverlayPill(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    onClick: (() -> Unit)? = null,
) {
    Text(
        text,
        style = LabTheme.typography.label,
        color = LabSurfaces.onOverlay,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .clip(LabShapes.pill)
                .background(LabSurfaces.overlay)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A block nested inside a Section: a titled group without a second border. */
@Composable
fun SubSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LabDimens.gap)) {
        SubHeading(title)
        Column(
            Modifier.padding(start = LabDimens.gap),
            verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
            content = content,
        )
    }
}

/**
 * A clickable list row in the IntelliJ list style: hover highlight, selection background
 * (history entries, items to inspect, the shell's sidebar).
 */
@Composable
fun SelectableRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = LabTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(LabShapes.small)
            .background(
                when {
                    selected -> colors.selection
                    hovered -> colors.raised
                    else -> Color.Transparent
                },
            ).hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = LabDimens.gap, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LabDimens.gap),
        content = content,
    )
}

/** A colour sample, optionally selectable. */
@Composable
fun ColorSwatch(
    color: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier
            .size(20.dp)
            .clip(LabShapes.small)
            .background(color)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) LabTheme.colors.accent else LabTheme.colors.border,
                LabShapes.small,
            ).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}
