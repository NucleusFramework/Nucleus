package dev.nucleusframework.lab.probes.fixtures.partialredraw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.DropdownItem
import dev.nucleusframework.lab.designsystem.LabLazyColumn
import dev.nucleusframework.lab.designsystem.LabShapes
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.PopupMenu
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.designsystem.TextField
import kotlinx.coroutines.delay

private val TOUR_MENU = listOf(DropdownItem("First") {}, DropdownItem("Second") {})

/** A dropdown opening and closing: a popup over the window, i.e. more than one scene owner. */
@Composable
internal fun TourMenu() {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(600)
            open = !open
        }
    }
    Box {
        Text("Menu", Modifier.background(LabTheme.colors.raised, LabShapes.small).padding(6.dp))
        PopupMenu(open, TOUR_MENU, onDismissRequest = { open = false })
    }
}

@Composable
internal fun ScrollingList(scroll: Boolean) {
    val state = rememberLazyListState()
    LaunchedEffect(scroll) {
        while (scroll) {
            delay(16)
            if (!state.canScrollForward) state.scrollToItem(0) else state.dispatchRawDelta(3f)
        }
    }
    LabLazyColumn(Modifier.width(200.dp).fillMaxSize().background(LabTheme.colors.background), state = state) {
        items(200) { i -> Text("Row $i", Modifier.padding(4.dp)) }
    }
}

@Composable
internal fun ReorderingList(reorder: Boolean) {
    val items = remember { mutableStateListOf(*Array(8) { it }) }
    LaunchedEffect(reorder) {
        while (reorder) {
            delay(400)
            items.add(items.removeAt(0))
        }
    }
    val selection = LabTheme.colors.selection
    LabLazyColumn(Modifier.width(160.dp).fillMaxSize().background(LabTheme.colors.code)) {
        items(items, key = { it }) { item ->
            Text(
                "Item $item",
                Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .padding(4.dp)
                    .background(selection)
                    .padding(4.dp),
            )
        }
    }
}

@Composable
internal fun Caret(focus: Boolean) {
    var text by remember { mutableStateOf("Caret") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focus) {
        if (focus) {
            focusRequester.requestFocus()
            repeat(5) {
                delay(250)
                text += "!"
            }
        }
    }
    TextField(text, { text = it }, Modifier.width(220.dp).focusRequester(focusRequester))
}
