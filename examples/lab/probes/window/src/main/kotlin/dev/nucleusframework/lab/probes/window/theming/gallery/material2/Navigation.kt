package dev.nucleusframework.lab.probes.window.theming.gallery.material2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.BottomAppBar
import androidx.compose.material.BottomNavigation
import androidx.compose.material.BottomNavigationItem
import androidx.compose.material.FabPosition
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LeadingIconTab
import androidx.compose.material.MaterialTheme
import androidx.compose.material.NavigationRail
import androidx.compose.material.NavigationRailItem
import androidx.compose.material.Scaffold
import androidx.compose.material.ScrollableTabRow
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private val destinations: List<Pair<String, ImageVector>> =
    listOf(
        "Home" to Icons.Filled.Home,
        "Favourites" to Icons.Filled.Favorite,
        "Profile" to Icons.Filled.Person,
        "Settings" to Icons.Filled.Settings,
    )

@Composable
internal fun AppBarsPage() {
    Page {
        Demo("Top app bars") {
            var last by remember { mutableStateOf("none") }
            TopAppBar(
                title = { Text("Primary") },
                navigationIcon = {
                    IconButton(onClick = { last = "menu" }) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                },
                actions = {
                    IconButton(onClick = { last = "search" }) { Icon(Icons.Filled.Search, "Search") }
                    IconButton(onClick = { last = "more" }) { Icon(Icons.Filled.MoreVert, "More") }
                },
            )
            TopAppBar(
                title = { Text("Surface") },
                backgroundColor = MaterialTheme.colors.surface,
                navigationIcon = {
                    IconButton(onClick = { last = "back" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                elevation = 4.dp,
            )
            StateLine("Last action: $last")
        }
        Demo("Bottom app bar with a docked FAB") {
            var added by remember { mutableIntStateOf(0) }
            Frame(height = 240) {
                Scaffold(
                    bottomBar = {
                        BottomAppBar(cutoutShape = CircleShape) {
                            IconButton(onClick = {}) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = {}) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                        }
                    },
                    floatingActionButton = {
                        FloatingActionButton(onClick = { added++ }) { Icon(Icons.Filled.Add, "Add") }
                    },
                    floatingActionButtonPosition = FabPosition.Center,
                    isFloatingActionButtonDocked = true,
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text("FAB pressed $added times")
                    }
                }
            }
        }
        Demo("Bottom navigation") {
            var selected by remember { mutableIntStateOf(0) }
            BottomNavigation {
                destinations.forEachIndexed { index, (label, icon) ->
                    BottomNavigationItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label) },
                    )
                }
            }
            StateLine("Destination: ${destinations[selected].first}")
        }
        Demo("Navigation rail") {
            var selected by remember { mutableIntStateOf(0) }
            Frame(height = 280) {
                Row {
                    NavigationRail {
                        destinations.forEachIndexed { index, (label, icon) ->
                            NavigationRailItem(
                                selected = selected == index,
                                onClick = { selected = index },
                                icon = { Icon(icon, contentDescription = null) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(destinations[selected].first, style = MaterialTheme.typography.h5)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TabsPage() {
    Page {
        Demo("Fixed tabs") {
            val titles = listOf("Overview", "Specs", "Reviews")
            var selected by remember { mutableIntStateOf(0) }
            TabRow(selectedTabIndex = selected) {
                titles.forEachIndexed { index, title ->
                    Tab(selected = selected == index, onClick = { selected = index }, text = { Text(title) })
                }
            }
            TabBody("${titles[selected]} content")
        }
        Demo("Icon and leading-icon tabs") {
            var selected by remember { mutableIntStateOf(0) }
            var leading by remember { mutableIntStateOf(1) }
            TabRow(selectedTabIndex = selected) {
                destinations.take(3).forEachIndexed { index, (label, icon) ->
                    Tab(
                        selected = selected == index,
                        onClick = { selected = index },
                        text = { Text(label) },
                        icon = { Icon(icon, contentDescription = null) },
                    )
                }
            }
            TabRow(selectedTabIndex = leading, backgroundColor = MaterialTheme.colors.surface) {
                destinations.take(3).forEachIndexed { index, (label, icon) ->
                    LeadingIconTab(
                        selected = leading == index,
                        onClick = { leading = index },
                        text = { Text(label) },
                        icon = { Icon(icon, contentDescription = null) },
                        selectedContentColor = MaterialTheme.colors.primary,
                    )
                }
            }
        }
        Demo("Scrollable tabs") {
            val chapters = (1..14).map { "Chapter $it" }
            var selected by remember { mutableIntStateOf(0) }
            ScrollableTabRow(selectedTabIndex = selected) {
                chapters.forEachIndexed { index, title ->
                    Tab(selected = selected == index, onClick = { selected = index }, text = { Text(title) })
                }
            }
            TabBody("${chapters[selected]} of ${chapters.size}")
        }
    }
}

@Composable
private fun TabBody(text: String) {
    Column(Modifier.fillMaxWidth().height(72.dp), verticalArrangement = Arrangement.Center) {
        Text(text, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.body1)
    }
}
