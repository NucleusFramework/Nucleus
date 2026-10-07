@file:OptIn(ExperimentalMaterialApi::class)

package dev.nucleusframework.lab.probes.window.theming.gallery.material2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.BackdropScaffold
import androidx.compose.material.BackdropValue
import androidx.compose.material.BottomDrawer
import androidx.compose.material.BottomDrawerValue
import androidx.compose.material.BottomSheetScaffold
import androidx.compose.material.Button
import androidx.compose.material.DrawerValue
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.ListItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.ModalDrawer
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.rememberBackdropScaffoldState
import androidx.compose.material.rememberBottomDrawerState
import androidx.compose.material.rememberBottomSheetScaffoldState
import androidx.compose.material.rememberDrawerState
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val folders = listOf("Inbox" to Icons.Filled.Inbox, "Starred" to Icons.Filled.Star, "Sent" to Icons.Filled.Send)

@Composable
internal fun SheetsPage() {
    Page {
        Demo("Modal drawer: open with the menu, or swipe from the leading edge") { ModalDrawerDemo() }
        Demo("Bottom drawer") { BottomDrawerDemo() }
        Demo("Modal bottom sheet") { ModalSheetDemo() }
        Demo("Bottom sheet scaffold: drag the peeking sheet up") { SheetScaffoldDemo() }
        Demo("Backdrop: the front layer reveals the back layer") { BackdropDemo() }
    }
}

@Composable
private fun ModalDrawerDemo() {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(folders.first().first) }
    Frame {
        ModalDrawer(
            drawerState = drawerState,
            drawerContent = {
                FolderList { picked ->
                    folder = picked
                    scope.launch { drawerState.close() }
                }
            },
        ) {
            Column {
                TopAppBar(
                    title = { Text(folder) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open drawer")
                        }
                    },
                )
                Centered("${drawerState.currentValue} · showing $folder")
            }
        }
    }
}

@Composable
private fun BottomDrawerDemo() {
    val drawerState = rememberBottomDrawerState(BottomDrawerValue.Closed)
    val scope = rememberCoroutineScope()
    Frame {
        BottomDrawer(
            drawerState = drawerState,
            drawerContent = { FolderList { scope.launch { drawerState.close() } } },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = { scope.launch { drawerState.open() } }) { Text("Open bottom drawer") }
            }
        }
    }
}

@Composable
private fun ModalSheetDemo() {
    val sheetState = rememberModalBottomSheetState(ModalBottomSheetValue.Hidden)
    val scope = rememberCoroutineScope()
    var shared by remember { mutableStateOf("nothing yet") }
    Frame {
        ModalBottomSheetLayout(
            sheetState = sheetState,
            sheetContent = {
                Text("Share with", Modifier.padding(16.dp), style = MaterialTheme.typography.subtitle1)
                listOf("Mail", "Messages", "Drive").forEach { target ->
                    ListItem(
                        modifier =
                            Modifier.padding(horizontal = 8.dp).fillMaxWidth().clickable {
                                shared = target
                                scope.launch { sheetState.hide() }
                            },
                        text = { Text(target) },
                    )
                }
            },
        ) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(onClick = { scope.launch { sheetState.show() } }) { Text("Share…") }
                StateLine("Shared to: $shared")
            }
        }
    }
}

@Composable
private fun SheetScaffoldDemo() {
    val state = rememberBottomSheetScaffoldState()
    Frame {
        BottomSheetScaffold(
            scaffoldState = state,
            sheetPeekHeight = 56.dp,
            sheetContent = {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    Text("Swipe up to expand", style = MaterialTheme.typography.subtitle1)
                }
                Column(Modifier.fillMaxWidth().height(180.dp).padding(16.dp)) {
                    Text("The sheet's content, revealed as it expands.")
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Sheet is ${state.bottomSheetState.currentValue}")
            }
        }
    }
}

@Composable
private fun BackdropDemo() {
    val state = rememberBackdropScaffoldState(BackdropValue.Concealed)
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(folders.first().first) }
    Frame(height = 360) {
        BackdropScaffold(
            scaffoldState = state,
            appBar = {
                TopAppBar(
                    title = { Text("Backdrop") },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch { if (state.isConcealed) state.reveal() else state.conceal() }
                        }) {
                            Icon(
                                if (state.isConcealed) Icons.Filled.Menu else Icons.Filled.Close,
                                contentDescription = "Toggle the back layer",
                            )
                        }
                    },
                    elevation = 0.dp,
                    backgroundColor = MaterialTheme.colors.primary,
                )
            },
            backLayerContent = {
                FolderList(onPrimary = true) { picked ->
                    folder = picked
                    scope.launch { state.conceal() }
                }
            },
            frontLayerContent = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Front layer · $folder") }
            },
        )
    }
}

@Composable
private fun FolderList(
    onPrimary: Boolean = false,
    onPick: (String) -> Unit,
) {
    val tint = if (onPrimary) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface
    Column(Modifier.padding(vertical = 8.dp)) {
        folders.forEach { (name, icon) ->
            ListItem(
                modifier = Modifier.clickable { onPick(name) },
                text = { Text(name, color = tint) },
                icon = { Icon(icon, contentDescription = null, tint = tint) },
            )
        }
    }
}

@Composable
private fun ColumnScope.Centered(text: String) {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text(text) }
}
