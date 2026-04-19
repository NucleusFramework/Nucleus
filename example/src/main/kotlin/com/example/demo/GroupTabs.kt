package com.example.demo

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

internal data class TabGroup(
    val name: String,
    val children: List<String>,
)

private val TAB_HEIGHT = 28.dp

/**
 * Parent tabs with per-tab dropdowns for child selection. Single-child groups
 * bypass the dropdown and select the child directly. Visuals match
 * [DraggableTabs] — same chip metrics, same hover/selection palette, no
 * elevation or heavyweight Material scaffolding on the popup.
 */
@Suppress("FunctionNaming")
@Composable
internal fun GroupDropdownTabs(
    groups: List<TabGroup>,
    selectedTab: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(TAB_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (group in groups) {
            val isSelected = selectedTab in group.children
            val isSingle = group.children.size == 1
            var expanded by remember { mutableStateOf(false) }

            Box {
                GroupTabChip(
                    label = group.name,
                    isSelected = isSelected,
                    onClick = {
                        if (isSingle) {
                            onSelect(group.children.single())
                        } else {
                            expanded = !expanded
                        }
                    },
                )

                if (expanded) {
                    val offsetY = with(LocalDensity.current) { (TAB_HEIGHT + 4.dp).roundToPx() }
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, offsetY),
                        onDismissRequest = { expanded = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        TabDropdownSurface {
                            for (child in group.children) {
                                DropdownItem(
                                    label = child,
                                    isSelected = child == selectedTab,
                                    onClick = {
                                        onSelect(child)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun GroupTabChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        when {
            isSelected -> MaterialTheme.colorScheme.surfaceContainerHigh
            isHovered -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        spring(stiffness = Spring.StiffnessMediumLow),
    )
    val textColor by animateColorAsState(
        when {
            isSelected -> MaterialTheme.colorScheme.onSurface
            isHovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    val indicatorAlpha by animateFloatAsState(
        if (isSelected) 1f else 0f,
        spring(stiffness = Spring.StiffnessMediumLow),
    )
    val indicatorColor = MaterialTheme.colorScheme.primary

    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(bgColor)
                .hoverable(hoverInteraction)
                .clickable { onClick() }
                .drawBehind {
                    if (indicatorAlpha > 0f) {
                        val h = 2.dp.toPx()
                        drawRoundRect(
                            color = indicatorColor.copy(alpha = indicatorAlpha),
                            topLeft = Offset(4.dp.toPx(), size.height - h),
                            size = Size(size.width - 8.dp.toPx(), h),
                            cornerRadius = CornerRadius(h / 2),
                        )
                    }
                }.padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun TabDropdownSurface(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .width(IntrinsicSize.Max)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                ).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        content()
    }
}

@Suppress("FunctionNaming")
@Composable
private fun DropdownItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        when {
            isSelected -> MaterialTheme.colorScheme.surfaceContainerHigh
            isHovered -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        spring(stiffness = Spring.StiffnessMediumLow),
    )
    val textColor by animateColorAsState(
        when {
            isSelected -> MaterialTheme.colorScheme.onSurface
            isHovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(bgColor)
                .hoverable(hoverInteraction)
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
