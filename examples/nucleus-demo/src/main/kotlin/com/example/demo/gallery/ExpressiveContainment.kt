@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.example.demo.gallery

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
internal fun ExpressiveContainment() {
    ParentSection(title = "Expressive containment") {
        ChildSection(title = "Material shapes") { MaterialShapesDemo() }
        ChildSection(title = "Expressive list items") { ExpressiveListItemsDemo() }
        ChildSection(title = "Carousel") { CarouselDemo() }
        ChildSection(title = "Motion scheme") { MotionSchemeDemo() }
        ChildSection(title = "Emphasized type") { EmphasizedTypeDemo() }
    }
}

@Composable
private fun MaterialShapesDemo() {
    val shapes =
        listOf(
            "Cookie 9" to MaterialShapes.Cookie9Sided,
            "Sunny" to MaterialShapes.Sunny,
            "Clover" to MaterialShapes.Clover4Leaf,
            "Pill" to MaterialShapes.Pill,
            "Gem" to MaterialShapes.Gem,
            "Boom" to MaterialShapes.SoftBoom,
            "Flower" to MaterialShapes.Flower,
            "Heart" to MaterialShapes.Heart,
        )
    OutlinedCard {
        FlowRow(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            shapes.forEach { (label, polygon) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier =
                            Modifier
                                .size(56.dp)
                                .clip(polygon.toShape())
                                .background(MaterialTheme.colorScheme.primary),
                    )
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ExpressiveListItemsDemo() {
    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
        ) {
            var selected by remember { mutableStateOf(0) }
            var notificationsOn by remember { mutableStateOf(true) }
            ListItem(
                selected = selected == 0,
                onClick = { selected = 0 },
                supportingContent = { Text("3 new messages") },
                leadingContent = { Icon(Icons.Filled.Inbox, contentDescription = null) },
                trailingContent = { Text("10:24") },
                colors =
                    ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 3),
            ) {
                Text("Inbox")
            }
            ListItem(
                checked = notificationsOn,
                onCheckedChange = { notificationsOn = it },
                supportingContent = { Text("Mentions and replies") },
                leadingContent = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                trailingContent = {
                    Switch(checked = notificationsOn, onCheckedChange = { notificationsOn = it })
                },
                colors =
                    ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                shapes = ListItemDefaults.segmentedShapes(index = 1, count = 3),
            ) {
                Text("Notifications")
            }
            ListItem(
                selected = selected == 2,
                onClick = { selected = 2 },
                supportingContent = { Text("Family, work, later") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                colors =
                    ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                shapes = ListItemDefaults.segmentedShapes(index = 2, count = 3),
            ) {
                Text("Labels")
            }
        }
    }
}

@Composable
private fun CarouselDemo() {
    val colors =
        listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.tertiary,
        )
    OutlinedCard {
        Box(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
        ) {
            HorizontalMultiBrowseCarousel(
                state = rememberCarouselState { colors.size },
                preferredItemWidth = 148.dp,
                itemSpacing = 8.dp,
                modifier = Modifier.height(140.dp),
            ) { index ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .maskClip(MaterialTheme.shapes.extraLarge)
                            .background(colors[index]),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Item ${index + 1}", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun MotionSchemeDemo() {
    var expanded by remember { mutableStateOf(false) }
    val size by animateDpAsState(
        targetValue = if (expanded) 180.dp else 72.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "expressive-motion",
    )
    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "MotionScheme.expressive() spatial spec — tap the shape",
                style = MaterialTheme.typography.bodyMedium,
            )
            Box(
                modifier =
                    Modifier
                        .size(size)
                        .clip(MaterialShapes.Cookie12Sided.toShape())
                        .background(MaterialTheme.colorScheme.tertiary)
                        .clickable { expanded = !expanded },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (expanded) "Shrink" else "Grow",
                    color = MaterialTheme.colorScheme.onTertiary,
                )
            }
        }
    }
}

@Composable
private fun EmphasizedTypeDemo() {
    val typography = MaterialTheme.typography
    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
        ) {
            Text("Display large emphasized", style = typography.displayLargeEmphasized)
            Text("Headline medium emphasized", style = typography.headlineMediumEmphasized)
            Text("Title large emphasized", style = typography.titleLargeEmphasized)
            Text("Body large emphasized", style = typography.bodyLargeEmphasized)
            Text("Label large emphasized", style = typography.labelLargeEmphasized)
        }
    }
}
