@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.nucleusframework.lab.probes.window.theming.gallery.material3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Seed of the gallery's colour schemes: Material's baseline purple. */
val Material3GallerySeed = Color(0xFF6750A4)

/** A gallery page: where it sits in the drawer, and what it draws. */
private enum class Material3Page(
    val group: String,
    val title: String,
    val icon: ImageVector,
) {
    Actions("Components", "Actions", Icons.Filled.SmartButton),
    TextInputs("Components", "Text inputs", Icons.Filled.Keyboard),
    Communication("Components", "Communication", Icons.Filled.Chat),
    Navigation("Components", "Navigation", Icons.Filled.Explore),
    Containment("Components", "Containment", Icons.Filled.Inventory2),
    Selection("Components", "Selection", Icons.Filled.CheckBox),
    ExpressiveActions("Expressive", "Actions", Icons.Filled.AutoAwesome),
    ExpressiveIndicators("Expressive", "Indicators", Icons.Filled.AutoAwesome),
    ExpressiveNavigation("Expressive", "Navigation", Icons.Filled.AutoAwesome),
    ExpressiveContainment("Expressive", "Containment", Icons.Filled.AutoAwesome),
    ExpressiveSelection("Expressive", "Selection", Icons.Filled.AutoAwesome),
    ColorScheme("Foundations", "Color", Icons.Filled.FormatPaint),
    TypeScale("Foundations", "Typography", Icons.AutoMirrored.Filled.TextSnippet),
    Elevation("Foundations", "Elevation", Icons.Filled.Opacity),
}

/**
 * The Material 3 component gallery: a permanent navigation drawer of pages, the Expressive
 * ones drawn under `MotionScheme.expressive()`. Expects a `MaterialTheme` around it.
 */
@Composable
fun Material3Gallery(
    seedColor: Color,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableStateOf(Material3Page.Actions) }
    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            PermanentDrawerSheet(Modifier.width(232.dp).fillMaxHeight()) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    Material3Page.entries.groupBy { it.group }.forEach { (group, pages) ->
                        Text(
                            group,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        )
                        pages.forEach { entry ->
                            NavigationDrawerItem(
                                icon = { Icon(entry.icon, contentDescription = null) },
                                label = { Text(entry.title) },
                                selected = page == entry,
                                onClick = { page = entry },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                    }
                }
            }
            VerticalDivider()
            Column(Modifier.weight(1f).fillMaxHeight()) {
                PageContent(page, seedColor, snackbarHostState)
            }
        }
    }
}

@Composable
private fun PageContent(
    page: Material3Page,
    seedColor: Color,
    snackbarHostState: SnackbarHostState,
) {
    when (page) {
        Material3Page.Actions -> ScrollingPage { Actions() }
        Material3Page.TextInputs -> ScrollingPage { TextInputs() }
        Material3Page.Communication -> ScrollingPage { Communication(snackbarHostState) }
        Material3Page.Navigation -> ScrollingPage { GalleryNavigationSection() }
        Material3Page.Containment -> ScrollingPage { Containment() }
        Material3Page.Selection -> ScrollingPage { Selection() }
        Material3Page.ExpressiveActions -> ExpressivePage { ExpressiveActions() }
        Material3Page.ExpressiveIndicators -> ExpressivePage { ExpressiveIndicators() }
        Material3Page.ExpressiveNavigation -> ExpressivePage { ExpressiveNavigation() }
        Material3Page.ExpressiveContainment -> ExpressivePage { ExpressiveContainment() }
        Material3Page.ExpressiveSelection -> ExpressivePage { ExpressiveSelection() }
        Material3Page.ColorScheme -> ColorScreen(seedColor)
        Material3Page.TypeScale -> TypographyScreen()
        Material3Page.Elevation -> ElevationScreen()
    }
}

@Composable
private fun ScrollingPage(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

/** The theme as it is, with the expressive motion scheme: what makes springs and morphs bounce. */
@Composable
private fun ExpressivePage(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        motionScheme = MotionScheme.expressive(),
    ) {
        ScrollingPage(content)
    }
}
