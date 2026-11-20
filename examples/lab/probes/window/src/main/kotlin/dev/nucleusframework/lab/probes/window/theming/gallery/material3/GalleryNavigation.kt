package dev.nucleusframework.lab.probes.window.theming.gallery.material3

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun GalleryNavigationSection() {
    ParentSection(title = "Navigation") {
        ChildSection(title = "Bottom app bar") { GalleryBottomAppBar() }
        ChildSection(title = "Navigation bar") { GalleryNavigationBar() }
        ChildSection(title = "Navigation drawer") { GalleryNavigationDrawer() }
        ChildSection(title = "Navigation rail") { GalleryNavigationRail() }
        ChildSection(title = "Tabs") { GalleryTabs() }
        ChildSection(title = "Top app bars") { GalleryTopAppBars() }
    }
}

@Composable
private fun GalleryBottomAppBar() {
    OutlinedCard {
        Box(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
        ) {
            BottomAppBar(
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                    IconButton(onClick = {}) { Icon(Icons.Filled.Search, contentDescription = null) }
                    IconButton(onClick = {}) { Icon(Icons.Filled.Favorite, contentDescription = null) }
                },
                floatingActionButton = {
                    FloatingActionButton(onClick = {}) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                },
            )
        }
    }
}

@Composable
private fun GalleryNavigationBar() {
    OutlinedCard {
        Box(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
        ) {
            var selected by remember { mutableStateOf(0) }
            NavigationBar {
                NavigationBarItem(
                    selected = selected == 0,
                    onClick = { selected = 0 },
                    icon = {
                        Icon(
                            imageVector = if (selected == 0) Icons.Default.Explore else Icons.Outlined.Explore,
                            contentDescription = null,
                        )
                    },
                    label = { Text("Explore") },
                )
                NavigationBarItem(
                    selected = selected == 1,
                    onClick = { selected = 1 },
                    icon = {
                        Icon(
                            imageVector = if (selected == 1) Icons.Default.Pets else Icons.Outlined.Pets,
                            contentDescription = null,
                        )
                    },
                    label = { Text("Pets") },
                )
                NavigationBarItem(
                    selected = selected == 2,
                    onClick = { selected = 2 },
                    icon = {
                        Icon(
                            imageVector = if (selected == 2) Icons.Default.AccountBox else Icons.Outlined.AccountBox,
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
private fun GalleryNavigationDrawer() {
    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                border = BorderStroke(DividerDefaults.Thickness.value.dp, DividerDefaults.color),
            ) {
                PermanentNavigationDrawer(
                    modifier = Modifier.width(300.dp),
                    drawerContent = {
                        PermanentDrawerSheet { NavigationDrawerContent() }
                    },
                ) {}
            }
        }
    }
}

@Composable
private fun NavigationDrawerContent() {
    Column(modifier = Modifier.padding(8.dp)) {
        var selected by remember { mutableStateOf(0) }
        Text("Mail", modifier = Modifier.padding(16.dp))
        NavigationDrawerItem(
            selected = selected == 0,
            onClick = { selected = 0 },
            icon = {
                Icon(
                    imageVector = if (selected == 0) Icons.Default.Inbox else Icons.Outlined.Inbox,
                    contentDescription = null,
                )
            },
            label = { Text("Inbox") },
        )
        NavigationDrawerItem(
            selected = selected == 1,
            onClick = { selected = 1 },
            icon = {
                Icon(
                    imageVector =
                        if (selected ==
                            1
                        ) {
                            Icons.AutoMirrored.Default.Send
                        } else {
                            Icons.AutoMirrored.Outlined.Send
                        },
                    contentDescription = null,
                )
            },
            label = { Text("Outbox") },
        )
        NavigationDrawerItem(
            selected = selected == 2,
            onClick = { selected = 2 },
            icon = {
                Icon(
                    imageVector =
                        if (selected ==
                            2
                        ) {
                            Icons.Default.Favorite
                        } else {
                            Icons.Default.FavoriteBorder
                        },
                    contentDescription = null,
                )
            },
            label = { Text("Favorite") },
        )
        NavigationDrawerItem(
            selected = selected == 3,
            onClick = { selected = 3 },
            icon = {
                Icon(
                    imageVector = if (selected == 3) Icons.Default.Delete else Icons.Outlined.Delete,
                    contentDescription = null,
                )
            },
            label = { Text("Trash") },
        )
        HorizontalDivider(Modifier.padding(8.dp))
        Text("Labels", modifier = Modifier.padding(16.dp))
        NavigationDrawerItem(
            selected = selected == 4,
            onClick = { selected = 4 },
            icon = {
                Icon(
                    imageVector =
                        if (selected ==
                            4
                        ) {
                            Icons.Default.Bookmark
                        } else {
                            Icons.Default.BookmarkBorder
                        },
                    contentDescription = null,
                )
            },
            label = { Text("Family") },
        )
        NavigationDrawerItem(
            selected = selected == 5,
            onClick = { selected = 5 },
            icon = {
                Icon(
                    imageVector =
                        if (selected ==
                            5
                        ) {
                            Icons.Default.Bookmark
                        } else {
                            Icons.Default.BookmarkBorder
                        },
                    contentDescription = null,
                )
            },
            label = { Text("School") },
        )
    }
}

@Composable
private fun GalleryNavigationRail() {
    OutlinedCard {
        Box(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(shadowElevation = 8.dp) {
                var selected by remember { mutableStateOf(0) }
                NavigationRail(
                    modifier = Modifier.padding(6.dp),
                    header = {
                        FloatingActionButton(onClick = {}) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                        }
                    },
                ) {
                    Spacer(Modifier.size(40.dp))
                    NavigationRailItem(
                        selected = selected == 0,
                        onClick = { selected = 0 },
                        icon = {
                            Icon(
                                imageVector =
                                    if (selected ==
                                        0
                                    ) {
                                        Icons.Default.Inbox
                                    } else {
                                        Icons.Outlined.Inbox
                                    },
                                contentDescription = null,
                            )
                        },
                        label = { Text("Inbox") },
                    )
                    NavigationRailItem(
                        selected = selected == 1,
                        onClick = { selected = 1 },
                        icon = {
                            Icon(
                                imageVector =
                                    if (selected ==
                                        1
                                    ) {
                                        Icons.AutoMirrored.Filled.Send
                                    } else {
                                        Icons.AutoMirrored.Outlined.Send
                                    },
                                contentDescription = null,
                            )
                        },
                        label = { Text("Outbox") },
                    )
                    NavigationRailItem(
                        selected = selected == 2,
                        onClick = { selected = 2 },
                        icon = {
                            Icon(
                                imageVector =
                                    if (selected ==
                                        2
                                    ) {
                                        Icons.Default.Favorite
                                    } else {
                                        Icons.Default.FavoriteBorder
                                    },
                                contentDescription = null,
                            )
                        },
                        label = { Text("Favorite") },
                    )
                    NavigationRailItem(
                        selected = selected == 3,
                        onClick = { selected = 3 },
                        icon = {
                            Icon(
                                imageVector =
                                    if (selected ==
                                        3
                                    ) {
                                        Icons.Default.Delete
                                    } else {
                                        Icons.Outlined.Delete
                                    },
                                contentDescription = null,
                            )
                        },
                        label = { Text("Trash") },
                    )
                    Spacer(Modifier.size(40.dp))
                }
            }
        }
    }
}

@Composable
private fun GalleryTabs() {
    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
        ) {
            var selected by remember { mutableStateOf(0) }
            TabRow(selectedTabIndex = selected) {
                Tab(
                    selected = selected == 0,
                    onClick = { selected = 0 },
                    icon = { Icon(imageVector = Icons.Default.Videocam, contentDescription = null) },
                    text = { Text("Video") },
                )
                Tab(
                    selected = selected == 1,
                    onClick = { selected = 1 },
                    icon = { Icon(imageVector = Icons.Default.Image, contentDescription = null) },
                    text = { Text("Photo") },
                )
                Tab(
                    selected = selected == 2,
                    onClick = { selected = 2 },
                    icon = { Icon(imageVector = Icons.Default.MusicNote, contentDescription = null) },
                    text = { Text("Audio") },
                )
            }
            Spacer(Modifier.size(16.dp))

            var selected2 by remember { mutableStateOf(0) }
            val items = List(10) { "Tab #$it" }
            ScrollableTabRow(selectedTabIndex = selected2) {
                items.forEachIndexed { i, item ->
                    Tab(
                        selected = selected2 == i,
                        onClick = { selected2 = i },
                        text = { Text(item) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopAppBars() {
    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
        ) {
            @Composable
            fun RowScope.Actions() {
                IconButton(onClick = {}) { Icon(imageVector = Icons.Default.AttachFile, contentDescription = null) }
                IconButton(onClick = {}) { Icon(imageVector = Icons.Default.Event, contentDescription = null) }
                IconButton(onClick = {}) { Icon(imageVector = Icons.Default.MoreVert, contentDescription = null) }
            }

            CenterAlignedTopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                title = { Text("CenterAligned") },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                        )
                    }
                },
            )
            Spacer(Modifier.size(16.dp))
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                title = { Text("Small") },
                actions = { Actions() },
            )
            Spacer(Modifier.size(16.dp))
            MediumTopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                title = { Text("Medium") },
                actions = { Actions() },
            )
            Spacer(Modifier.size(16.dp))
            LargeTopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                title = { Text("Large") },
                actions = { Actions() },
            )
        }
    }
}
