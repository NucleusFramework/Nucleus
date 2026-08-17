@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.example.demo.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun ExpressiveNavigation() {
    ParentSection(title = "Expressive navigation") {
        ChildSection(title = "Wide navigation rail") { WideNavigationRailDemo() }
        ChildSection(title = "Short navigation bar") { ShortNavigationBarDemo() }
        ChildSection(title = "Flexible top app bars") { FlexibleTopAppBarsDemo() }
    }
}

@Composable
private fun WideNavigationRailDemo() {
    val state = rememberWideNavigationRailState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableIntStateOf(0) }
    val expanded = state.targetValue == WideNavigationRailValue.Expanded
    val items =
        listOf(
            Triple("Inbox", Icons.Filled.Inbox, Icons.Outlined.Inbox),
            Triple("Outbox", Icons.AutoMirrored.Filled.Send, Icons.Outlined.Send),
            Triple("Favorite", Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder),
            Triple("Trash", Icons.Filled.Delete, Icons.Outlined.Delete),
        )
    OutlinedCard {
        Box(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .height(360.dp)
                    .padding(16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            WideNavigationRail(
                modifier = Modifier.fillMaxHeight(),
                state = state,
                header = {
                    FilledIconButton(
                        onClick = {
                            scope.launch {
                                if (expanded) state.collapse() else state.expand()
                            }
                        },
                    ) {
                        Icon(
                            if (expanded) Icons.AutoMirrored.Filled.MenuOpen else Icons.Filled.Menu,
                            contentDescription = if (expanded) "Collapse rail" else "Expand rail",
                        )
                    }
                    FloatingActionButton(onClick = {}) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                    }
                },
            ) {
                items.forEachIndexed { index, (label, selectedIcon, unselectedIcon) ->
                    WideNavigationRailItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = {
                            Icon(
                                if (selected == index) selectedIcon else unselectedIcon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(label) },
                        railExpanded = expanded,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortNavigationBarDemo() {
    var selected by remember { mutableIntStateOf(0) }
    OutlinedCard {
        Box(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
        ) {
            ShortNavigationBar {
                ShortNavigationBarItem(
                    selected = selected == 0,
                    onClick = { selected = 0 },
                    icon = {
                        Icon(
                            if (selected == 0) Icons.Filled.Explore else Icons.Outlined.Explore,
                            contentDescription = null,
                        )
                    },
                    label = { Text("Explore") },
                )
                ShortNavigationBarItem(
                    selected = selected == 1,
                    onClick = { selected = 1 },
                    icon = {
                        Icon(
                            if (selected == 1) Icons.Filled.Pets else Icons.Outlined.Pets,
                            contentDescription = null,
                        )
                    },
                    label = { Text("Pets") },
                )
                ShortNavigationBarItem(
                    selected = selected == 2,
                    onClick = { selected = 2 },
                    icon = {
                        Icon(
                            if (selected == 2) Icons.Filled.AccountBox else Icons.Outlined.AccountBox,
                            contentDescription = null,
                        )
                    },
                    label = { Text("Account") },
                )
            }
        }
    }
}

@Composable
private fun FlexibleTopAppBarsDemo() {
    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MediumFlexibleTopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                title = { Text("Medium flexible") },
                subtitle = { Text("Expressive subtitle") },
                actions = { FlexibleActions() },
            )
            LargeFlexibleTopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                title = { Text("Large flexible") },
                subtitle = { Text("Two-row collapsing bar") },
                actions = { FlexibleActions() },
            )
        }
    }
}

@Composable
private fun FlexibleActions() {
    IconButton(onClick = {}) { Icon(Icons.Filled.AttachFile, contentDescription = null) }
    IconButton(onClick = {}) { Icon(Icons.Filled.Event, contentDescription = null) }
    IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
}
