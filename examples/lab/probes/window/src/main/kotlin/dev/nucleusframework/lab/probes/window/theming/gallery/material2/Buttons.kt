package dev.nucleusframework.lab.probes.window.theming.gallery.material2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.ExtendedFloatingActionButton
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.IconToggleButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ButtonsPage() {
    var clicks by remember { mutableIntStateOf(0) }
    val click: () -> Unit = { clicks++ }
    Page {
        Demo("Contained, outlined, text") {
            ButtonRow {
                Button(onClick = click) { Text("Contained") }
                Button(onClick = click) {
                    Icon(Icons.Filled.Send, contentDescription = null, ButtonIcon)
                    Spacer(IconSpacing)
                    Text("With icon")
                }
                Button(
                    onClick = click,
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary),
                ) { Text("Secondary") }
                Button(onClick = click, enabled = false) { Text("Disabled") }
            }
            ButtonRow {
                OutlinedButton(onClick = click) { Text("Outlined") }
                OutlinedButton(onClick = click) {
                    Icon(Icons.Filled.Share, contentDescription = null, ButtonIcon)
                    Spacer(IconSpacing)
                    Text("Share")
                }
                OutlinedButton(onClick = click, enabled = false) { Text("Disabled") }
            }
            ButtonRow {
                TextButton(onClick = click) { Text("Text") }
                TextButton(onClick = click) {
                    Icon(Icons.Filled.Edit, contentDescription = null, ButtonIcon)
                    Spacer(IconSpacing)
                    Text("Edit")
                }
                TextButton(onClick = click, enabled = false) { Text("Disabled") }
            }
            StateLine("Clicked $clicks times")
        }
        Demo("Icon buttons and toggles") {
            var favourite by remember { mutableStateOf(false) }
            var bookmarked by remember { mutableStateOf(true) }
            ButtonRow {
                IconButton(onClick = click) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                IconButton(onClick = click, enabled = false) { Icon(Icons.Filled.Delete, contentDescription = null) }
                IconToggleButton(checked = favourite, onCheckedChange = { favourite = it }) {
                    Icon(
                        if (favourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favourite",
                        tint = if (favourite) MaterialTheme.colors.secondary else MaterialTheme.colors.onSurface,
                    )
                }
                IconToggleButton(checked = bookmarked, onCheckedChange = { bookmarked = it }) {
                    Icon(
                        if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "Bookmark",
                    )
                }
            }
            StateLine("favourite = $favourite · bookmarked = $bookmarked")
        }
        Demo("Floating action buttons") {
            var extended by remember { mutableStateOf(true) }
            ButtonRow {
                FloatingActionButton(onClick = click) { Icon(Icons.Filled.Add, contentDescription = "Add") }
                FloatingActionButton(
                    onClick = click,
                    backgroundColor = MaterialTheme.colors.primary,
                ) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                ExtendedFloatingActionButton(
                    text = { Text(if (extended) "Collapse" else "Expand") },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { extended = !extended },
                )
                if (extended) {
                    ExtendedFloatingActionButton(text = { Text("Text only") }, onClick = click)
                }
            }
        }
    }
}

@Composable
private fun ButtonRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

private val ButtonIcon = Modifier.size(ButtonDefaults.IconSize)
