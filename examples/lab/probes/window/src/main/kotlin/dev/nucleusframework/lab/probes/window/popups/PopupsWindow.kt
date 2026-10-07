package dev.nucleusframework.lab.probes.window.popups

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LabSessionWindow
import dev.nucleusframework.lab.designsystem.LabShapes
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.TargetArea
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.designsystem.TextField
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.TaoWindow
import org.jetbrains.jewel.ui.component.PopupMenu

/**
 * A window full of popups anchored where they have the least room. Placement is the subject,
 * so everything wears the Lab's style: Jewel menus (`PopupMenu`, the specimen itself), a Lab
 * card in a bare `Popup`, a Lab dialog, a context target and a Jewel text field.
 */
@Composable
internal fun NucleusApplicationScope.PopupsWindow(
    vm: PopupsViewModel,
    close: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val config = state.config
    val layers = if (config.nativePopupLayers) "native" else "inline"
    val menus = if (config.nativeContextMenu) "native" else "Compose"
    // Both flags are creation-time: a new config means a new native window.
    key(config) {
        LabSessionWindow(
            title = "Popups lab",
            onCloseRequest = close,
            subtitle = "layers $layers · menus $menus",
            state = rememberWindowState(size = DpSize(720.dp, 520.dp)),
            nativePopupLayers = config.nativePopupLayers,
            nativeContextMenu = config.nativeContextMenu,
            scrollable = false,
        ) {
            val owner = LocalTaoWindow.current
            LaunchedEffect(owner) { owner?.let { vm.onIntent(PopupsIntent.Attached(it)) } }
            Box(Modifier.fillMaxSize()) {
                EdgeMenu("↖ menu", vm, owner, Alignment.Start, Modifier.align(Alignment.TopStart))
                EdgeMenu("↗ menu", vm, owner, Alignment.End, Modifier.align(Alignment.TopEnd))
                EdgeMenu("↙ menu", vm, owner, Alignment.Start, Modifier.align(Alignment.BottomStart))
                EdgeMenu("↘ menu", vm, owner, Alignment.End, Modifier.align(Alignment.BottomEnd))
                Column(
                    Modifier.align(Alignment.Center).width(320.dp),
                    verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
                ) {
                    OversizedPopup(vm, owner)
                    CenteredDialog(vm)
                    ContextTarget(vm)
                }
            }
        }
    }
}

/**
 * Reports where the popup hosting this node landed, as seen from inside it. `composed`, so
 * the host window is read where the node lives (inside the popup), not where the modifier
 * was built: a menu's modifier is built by its anchor.
 */
private fun Modifier.reportPopup(
    label: String,
    vm: PopupsViewModel,
    owner: TaoWindow?,
): Modifier =
    composed {
        val host = LocalTaoWindow.current
        onGloballyPositioned { coordinates ->
            val b = coordinates.boundsInWindow()
            val inHost = IntRect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
            val own = host != null && owner != null && host.handle != owner.handle
            // Undecorated popup windows: their outer origin is their content origin.
            val origin = (if (own) host else owner)?.outerBoundsPx()?.takeIf { it.size >= 2 }
            val screen = origin?.let { inHost.translate(it[0].toInt(), it[1].toInt()) }
            val work = vm.workArea()
            val fits = if (screen != null && work != null) screen.within(work) else null
            vm.onIntent(PopupsIntent.Measured(PopupReport(label, inHost, own, screen, fits)))
        }
    }

/** A Jewel `PopupMenu` anchored to its button, taller than the room it has. */
@Composable
private fun EdgeMenu(
    label: String,
    vm: PopupsViewModel,
    owner: TaoWindow?,
    alignment: Alignment.Horizontal,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        SecondaryAction(label) { expanded = !expanded }
        if (expanded) {
            PopupMenu(
                onDismissRequest = {
                    expanded = false
                    true
                },
                horizontalAlignment = alignment,
                modifier = Modifier.reportPopup(label, vm, owner),
            ) {
                repeat(MENU_ITEMS) { index ->
                    selectableItem(selected = false, onClick = {
                        vm.onIntent(PopupsIntent.Picked("$label → entry ${index + 1}"))
                        expanded = false
                    }) {
                        Text("Entry ${index + 1}")
                    }
                }
            }
        }
    }
}

@Composable
private fun OversizedPopup(
    vm: PopupsViewModel,
    owner: TaoWindow?,
) {
    var shown by remember { mutableStateOf(false) }
    PrimaryAction(
        if (shown) "Hide oversized panel" else "Show oversized panel (520×420 dp)",
        modifier = Modifier.fillMaxWidth(),
    ) { shown = !shown }
    if (shown) {
        Popup(alignment = Alignment.TopStart, onDismissRequest = { shown = false }) {
            PopupCard(Modifier.reportPopup("oversized panel", vm, owner)) {
                Text("Larger than its window", style = LabTheme.typography.heading)
                Hint("Inline layers clip it to the window; native layers let it out, but never past the work area.")
                Spacer(Modifier.size(width = 480.dp, height = 340.dp))
            }
        }
    }
}

/** The surface of a popup or dialog body: IntelliJ's popup background and border. */
@Composable
private fun PopupCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .clip(LabShapes.block)
            .background(LabTheme.colors.panel)
            .border(1.dp, LabTheme.colors.borderStrong, LabShapes.block)
            .padding(LabDimens.page),
        verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
    ) {
        content()
    }
}

@Composable
private fun CenteredDialog(vm: PopupsViewModel) {
    var shown by remember { mutableStateOf(false) }
    SecondaryAction("Open a window-centred dialog", modifier = Modifier.fillMaxWidth()) {
        shown = true
        vm.onIntent(PopupsIntent.Picked("dialog opened"))
    }
    if (shown) {
        Dialog(onDismissRequest = { shown = false }) {
            PopupCard(Modifier.width(360.dp)) {
                Text("Centred on the window", style = LabTheme.typography.heading)
                Text("A dialog belongs to its window: it must stay centred on it, not drift to the screen centre.")
                Actions { PrimaryAction("Close") { shown = false } }
            }
        }
    }
}

@Composable
private fun ContextTarget(vm: PopupsViewModel) {
    var text by remember { mutableStateOf("Right-click me for the text menu") }
    ContextMenuArea(items = {
        listOf("Inspect", "Duplicate", "Delete").map { name ->
            ContextMenuItem(name) { vm.onIntent(PopupsIntent.Picked("context → $name")) }
        }
    }) {
        TargetArea(Modifier.height(56.dp), label = "Right-click here: custom context menu")
    }
    TextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
}

private const val MENU_ITEMS = 14
