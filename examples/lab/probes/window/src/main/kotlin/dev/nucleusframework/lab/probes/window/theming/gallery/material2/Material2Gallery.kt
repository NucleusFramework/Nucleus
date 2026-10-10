package dev.nucleusframework.lab.probes.window.theming.gallery.material2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.ContentAlpha
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.LocalContentAlpha
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.ScaffoldState
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDownCircle
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** A gallery page and its entry in the side list. */
private enum class Material2Page(
    val title: String,
    val icon: ImageVector,
) {
    Typography("Typography", Icons.Filled.TextFields),
    Colors("Colors", Icons.Filled.Palette),
    Buttons("Buttons", Icons.Filled.SmartButton),
    Selection("Selection controls", Icons.Filled.CheckBox),
    Sliders("Sliders", Icons.Filled.Tune),
    TextFields("Text fields", Icons.Filled.Edit),
    Chips("Chips", Icons.Filled.Style),
    Lists("Lists", Icons.AutoMirrored.Filled.List),
    Surfaces("Cards & elevation", Icons.Filled.Layers),
    AppBars("App bars", Icons.Filled.WebAsset),
    Tabs("Tabs", Icons.Filled.Tab),
    Menus("Dialogs & menus", Icons.Filled.ArrowDropDownCircle),
    Feedback("Snackbars & progress", Icons.Filled.Notifications),
    Sheets("Drawers & sheets", Icons.Filled.ViewAgenda),
}

/**
 * The Material 2 component gallery: an elevated side list of pages next to a [Scaffold] whose
 * [TopAppBar] names the page, the way an M2 app lays out a permanent drawer. Expects a
 * `MaterialTheme` around it.
 */
@Composable
fun Material2Gallery(modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableStateOf(Material2Page.Typography) }
    val scaffoldState = rememberScaffoldState()
    Row(modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        Surface(Modifier.width(232.dp).fillMaxHeight(), elevation = 4.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Widgets, contentDescription = null, tint = MaterialTheme.colors.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("Material 2", style = MaterialTheme.typography.h6)
                }
                Divider()
                Material2Page.entries.forEach { entry ->
                    DrawerEntry(entry.title, entry.icon, selected = page == entry) { page = entry }
                }
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            scaffoldState = scaffoldState,
            topBar = { TopAppBar(title = { Text(page.title) }) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) { PageContent(page, scaffoldState) }
        }
    }
}

@Composable
private fun PageContent(
    page: Material2Page,
    scaffoldState: ScaffoldState,
) {
    when (page) {
        Material2Page.Typography -> TypographyPage()
        Material2Page.Colors -> ColorsPage()
        Material2Page.Buttons -> ButtonsPage()
        Material2Page.Selection -> SelectionPage()
        Material2Page.Sliders -> SlidersPage()
        Material2Page.TextFields -> TextFieldsPage()
        Material2Page.Chips -> ChipsPage()
        Material2Page.Lists -> ListsPage()
        Material2Page.Surfaces -> SurfacesPage()
        Material2Page.AppBars -> AppBarsPage()
        Material2Page.Tabs -> TabsPage()
        Material2Page.Menus -> MenusPage()
        Material2Page.Feedback -> FeedbackPage(scaffoldState.snackbarHostState)
        Material2Page.Sheets -> SheetsPage()
    }
}

/** An M2 navigation-drawer row: the selection is a primary tint behind icon and label. */
@Composable
private fun DrawerEntry(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colors
    val tint = if (selected) colors.primary else colors.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(
                if (selected) colors.primary.copy(alpha = 0.12f) else colors.surface,
                RoundedCornerShape(4.dp),
            ).selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentAlpha provides if (selected) 1f else ContentAlpha.medium) {
            Icon(icon, contentDescription = null, tint = tint.copy(alpha = LocalContentAlpha.current))
            Spacer(Modifier.width(24.dp))
            Text(title, style = MaterialTheme.typography.body2, color = tint)
        }
    }
}

/** A scrolling page of [Demo] cards. */
@Composable
internal fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** One demo: an elevated card with a caption, then the components. */
@Composable
internal fun Demo(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth(), elevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.subtitle1, color = MaterialTheme.colors.primary)
            content()
        }
    }
}

/** A fixed-height frame for components that want a screen of their own (scaffolds, drawers). */
@Composable
internal fun Frame(
    height: Int = 320,
    content: @Composable () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().height(height.dp),
        shape = RoundedCornerShape(4.dp),
        elevation = 1.dp,
        content = content,
    )
}

/** Muted line under a demo: what the state is now. */
@Composable
internal fun StateLine(text: String) {
    CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.medium) {
        Text(text, style = MaterialTheme.typography.caption)
    }
}

internal val IconSpacing = Modifier.size(8.dp)
