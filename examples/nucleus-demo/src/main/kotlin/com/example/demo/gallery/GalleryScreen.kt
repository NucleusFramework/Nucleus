package com.example.demo.gallery

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class GalleryTab(
    val title: String,
    val icon: ImageVector,
)

private val galleryTabs =
    listOf(
        GalleryTab("Components", Icons.Filled.Widgets),
        GalleryTab("Expressive", Icons.Filled.AutoAwesome),
        GalleryTab("Color", Icons.Filled.FormatPaint),
        GalleryTab("Typography", Icons.AutoMirrored.Filled.TextSnippet),
        GalleryTab("Elevation", Icons.Filled.Opacity),
    )

@Composable
internal fun GalleryScreen(seedColor: Color) {
    var selectedIndex by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        Row(
            modifier = Modifier.padding(paddingValues).consumeWindowInsets(paddingValues),
        ) {
            NavigationRail(modifier = Modifier.padding(6.dp)) {
                galleryTabs.forEachIndexed { index, tab ->
                    NavigationRailItem(
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index },
                    )
                }
            }
            when (selectedIndex) {
                0 -> ComponentScreen(snackbarHostState)
                1 -> ExpressiveScreen()
                2 -> ColorScreen(seedColor)
                3 -> TypographyScreen()
                4 -> ElevationScreen()
            }
        }
    }
}
