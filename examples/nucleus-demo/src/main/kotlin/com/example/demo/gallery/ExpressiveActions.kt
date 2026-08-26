@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.example.demo.gallery

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.VerticalFloatingToolbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun ExpressiveActions() {
    ParentSection(title = "Expressive actions") {
        ChildSection(title = "Button sizes") { ButtonSizesDemo() }
        ChildSection(title = "Toggle buttons") { ToggleButtonsDemo() }
        ChildSection(title = "Button group") { ButtonGroupDemo() }
        ChildSection(title = "Connected button groups") { ConnectedButtonGroupsDemo() }
        ChildSection(title = "Split buttons") { SplitButtonsDemo() }
        ChildSection(title = "Icon button sizes") { IconButtonSizesDemo() }
        ChildSection(title = "FAB menu") { FabMenuDemo() }
        ChildSection(title = "Floating toolbars") { FloatingToolbarsDemo() }
    }
}

@Composable
private fun ButtonSizesDemo() {
    val sizes =
        listOf(
            "XS" to ButtonDefaults.ExtraSmallContainerHeight,
            "S" to ButtonDefaults.MinHeight,
            "M" to ButtonDefaults.MediumContainerHeight,
            "L" to ButtonDefaults.LargeContainerHeight,
            "XL" to ButtonDefaults.ExtraLargeContainerHeight,
        )
    OutlinedCard {
        FlowRow(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            sizes.forEach { (label, size) ->
                Button(
                    onClick = {},
                    modifier = Modifier.heightIn(size),
                    shapes = ButtonDefaults.shapesFor(size),
                    contentPadding = ButtonDefaults.contentPaddingFor(size),
                ) {
                    Text(label, style = ButtonDefaults.textStyleFor(size))
                }
            }
        }
    }
}

@Composable
private fun ToggleButtonsDemo() {
    var filled by remember { mutableStateOf(true) }
    var tonal by remember { mutableStateOf(false) }
    var outlined by remember { mutableStateOf(true) }
    OutlinedCard {
        FlowRow(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToggleButton(checked = filled, onCheckedChange = { filled = it }) {
                Icon(
                    if (filled) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                )
                Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                Text("Filled")
            }
            TonalToggleButton(checked = tonal, onCheckedChange = { tonal = it }) {
                Icon(
                    if (tonal) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = null,
                )
                Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                Text("Tonal")
            }
            OutlinedToggleButton(checked = outlined, onCheckedChange = { outlined = it }) {
                Text("Outlined")
            }
        }
    }
}

@Composable
private fun ButtonGroupDemo() {
    OutlinedCard {
        Box(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
        ) {
            ButtonGroup(
                overflowIndicator = { menuState ->
                    ButtonGroupDefaults.OverflowIndicator(menuState = menuState)
                },
            ) {
                listOf("Cut", "Copy", "Paste", "Share", "Delete", "Archive", "Label").forEach { label ->
                    clickableItem(onClick = {}, label = label)
                }
            }
        }
    }
}

@Composable
private fun ConnectedButtonGroupsDemo() {
    val options = listOf("Work", "Restaurant", "Coffee", "Search", "Home")
    val uncheckedIcons =
        listOf(
            Icons.Outlined.Work,
            Icons.Outlined.Restaurant,
            Icons.Outlined.Coffee,
            Icons.Outlined.Search,
            Icons.Outlined.Home,
        )
    val checkedIcons =
        listOf(
            Icons.Filled.Work,
            Icons.Filled.Restaurant,
            Icons.Filled.Coffee,
            Icons.Filled.Search,
            Icons.Filled.Home,
        )
    var selectedIndex by remember { mutableIntStateOf(0) }
    val multiChecked = remember { mutableStateListOf(true, false, false, false, false) }

    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Single select", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEachIndexed { index, label ->
                    ToggleButton(
                        checked = selectedIndex == index,
                        onCheckedChange = { selectedIndex = index },
                        shapes =
                            when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                        modifier = Modifier.semantics { role = Role.RadioButton },
                    ) {
                        Icon(
                            if (selectedIndex == index) checkedIcons[index] else uncheckedIcons[index],
                            contentDescription = null,
                        )
                        Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                        Text(label)
                    }
                }
            }
            Text("Multi select", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEachIndexed { index, label ->
                    ToggleButton(
                        checked = multiChecked[index],
                        onCheckedChange = { multiChecked[index] = it },
                        shapes =
                            when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                    ) {
                        Icon(
                            if (multiChecked[index]) checkedIcons[index] else uncheckedIcons[index],
                            contentDescription = null,
                        )
                        Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitButtonsDemo() {
    var filled by remember { mutableStateOf(false) }
    var tonal by remember { mutableStateOf(false) }
    var outlined by remember { mutableStateOf(false) }
    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SplitButtonWithMenu(
                checked = filled,
                onCheckedChange = { filled = it },
                leading = {
                    SplitButtonDefaults.LeadingButton(onClick = {}) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Filled")
                    }
                },
                trailing = { onCheckedChange, checked ->
                    SplitButtonDefaults.TrailingButton(
                        checked = checked,
                        onCheckedChange = onCheckedChange,
                    ) {
                        SplitTrailingIcon(checked)
                    }
                },
            )
            SplitButtonWithMenu(
                checked = tonal,
                onCheckedChange = { tonal = it },
                leading = {
                    SplitButtonDefaults.TonalLeadingButton(onClick = {}) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Tonal")
                    }
                },
                trailing = { onCheckedChange, checked ->
                    SplitButtonDefaults.TonalTrailingButton(
                        checked = checked,
                        onCheckedChange = onCheckedChange,
                    ) {
                        SplitTrailingIcon(checked)
                    }
                },
            )
            SplitButtonWithMenu(
                checked = outlined,
                onCheckedChange = { outlined = it },
                leading = {
                    SplitButtonDefaults.OutlinedLeadingButton(onClick = {}) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Outlined")
                    }
                },
                trailing = { onCheckedChange, checked ->
                    SplitButtonDefaults.OutlinedTrailingButton(
                        checked = checked,
                        onCheckedChange = onCheckedChange,
                    ) {
                        SplitTrailingIcon(checked)
                    }
                },
            )
        }
    }
}

@Composable
private fun SplitButtonWithMenu(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leading: @Composable () -> Unit,
    trailing: @Composable (onCheckedChange: (Boolean) -> Unit, checked: Boolean) -> Unit,
) {
    Box {
        SplitButtonLayout(
            leadingButton = leading,
            trailingButton = { trailing(onCheckedChange, checked) },
        )
        DropdownMenu(expanded = checked, onDismissRequest = { onCheckedChange(false) }) {
            DropdownMenuItem(text = { Text("Draft") }, onClick = { onCheckedChange(false) })
            DropdownMenuItem(text = { Text("Schedule") }, onClick = { onCheckedChange(false) })
            DropdownMenuItem(text = { Text("Export") }, onClick = { onCheckedChange(false) })
        }
    }
}

@Composable
private fun SplitTrailingIcon(checked: Boolean) {
    val rotation by animateFloatAsState(if (checked) 180f else 0f, label = "split-chevron")
    Icon(
        Icons.Filled.KeyboardArrowDown,
        contentDescription = null,
        modifier =
            Modifier
                .size(SplitButtonDefaults.TrailingIconSize)
                .graphicsLayer { rotationZ = rotation },
    )
}

@Composable
private fun IconButtonSizesDemo() {
    val sizes =
        listOf(
            IconButtonDefaults.extraSmallContainerSize() to IconButtonDefaults.extraSmallIconSize,
            IconButtonDefaults.smallContainerSize() to IconButtonDefaults.smallIconSize,
            IconButtonDefaults.mediumContainerSize() to IconButtonDefaults.mediumIconSize,
            IconButtonDefaults.largeContainerSize() to IconButtonDefaults.largeIconSize,
            IconButtonDefaults.extraLargeContainerSize() to IconButtonDefaults.extraLargeIconSize,
        )
    var toggled by remember { mutableStateOf(true) }
    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sizes.forEach { (container, icon) ->
                    FilledIconButton(
                        onClick = {},
                        modifier = Modifier.size(container),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(icon))
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = {}) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                }
                OutlinedIconButton(onClick = {}) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                }
                FilledIconToggleButton(checked = toggled, onCheckedChange = { toggled = it }) {
                    Icon(
                        if (toggled) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun FabMenuDemo() {
    var expanded by remember { mutableStateOf(false) }
    val items =
        listOf(
            Icons.AutoMirrored.Filled.Message to "Reply",
            Icons.Filled.People to "Reply all",
            Icons.Filled.Contacts to "Forward",
            Icons.Filled.Snooze to "Snooze",
            Icons.Filled.Archive to "Archive",
            Icons.AutoMirrored.Filled.Label to "Label",
        )
    OutlinedCard {
        Box(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .height(280.dp)
                    .padding(16.dp),
        ) {
            FloatingActionButtonMenu(
                modifier = Modifier.align(Alignment.BottomEnd),
                expanded = expanded,
                button = {
                    ToggleFloatingActionButton(
                        checked = expanded,
                        onCheckedChange = { expanded = it },
                    ) {
                        val imageVector by remember {
                            derivedStateOf {
                                if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.Add
                            }
                        }
                        Icon(
                            painter = rememberVectorPainter(imageVector),
                            contentDescription = "Toggle menu",
                            modifier = Modifier.animateIcon({ checkedProgress }),
                        )
                    }
                },
            ) {
                items.forEach { (icon, label) ->
                    FloatingActionButtonMenuItem(
                        onClick = { expanded = false },
                        icon = { Icon(icon, contentDescription = null) },
                        text = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingToolbarsDemo() {
    var horizontalExpanded by remember { mutableStateOf(true) }
    var verticalExpanded by remember { mutableStateOf(true) }
    OutlinedCard {
        Row(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalFloatingToolbar(
                expanded = horizontalExpanded,
                floatingActionButton = {
                    FloatingToolbarDefaults.VibrantFloatingActionButton(
                        onClick = { horizontalExpanded = !horizontalExpanded },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                },
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            ) {
                IconButton(onClick = {}) { Icon(Icons.Filled.Edit, contentDescription = null) }
                IconButton(onClick = {}) { Icon(Icons.Filled.Favorite, contentDescription = null) }
                IconButton(onClick = {}) { Icon(Icons.Filled.Share, contentDescription = null) }
                IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
            }
            VerticalFloatingToolbar(
                expanded = verticalExpanded,
                floatingActionButton = {
                    FloatingToolbarDefaults.StandardFloatingActionButton(
                        onClick = { verticalExpanded = !verticalExpanded },
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                    }
                },
            ) {
                IconButton(onClick = {}) { Icon(Icons.Filled.Search, contentDescription = null) }
                IconButton(onClick = {}) { Icon(Icons.Filled.Download, contentDescription = null) }
                IconButton(onClick = {}) { Icon(Icons.Filled.Settings, contentDescription = null) }
            }
        }
    }
}
