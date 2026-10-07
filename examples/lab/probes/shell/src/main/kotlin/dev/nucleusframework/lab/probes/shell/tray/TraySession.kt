package dev.nucleusframework.lab.probes.shell.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.nucleusframework.composenativetray.tray.api.Tray
import dev.nucleusframework.lab.core.LabPaths
import dev.nucleusframework.lab.core.mvi.stamped

/**
 * The Lab's tray icon, composed as a session so it outlives navigation, like a real tray
 * icon outlives any one screen. Picks are stamped where ComposeNativeTray calls them.
 */
@Suppress("DEPRECATION") // The composable-icon overloads need a skiko API Compose 1.12 dropped.
@Composable
fun TraySession(
    vm: TrayViewModel,
    close: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val png = remember { iconUri("/lab/shell/tray-icon.png") }
    val ico = remember { iconUri("/lab/shell/tray-icon.ico") }
    DisposableEffect(Unit) {
        vm.onIntent(TrayIntent.Installed(true))
        onDispose { vm.onIntent(TrayIntent.Installed(false)) }
    }

    fun deliver(action: TrayAction) = vm.onIntent(TrayIntent.Delivered(action.stamped()))

    Tray(
        iconPath = png,
        windowsIconPath = ico,
        tooltip = state.tooltip,
        primaryAction = { deliver(TrayAction.Primary) },
        onMenuOpened = { deliver(TrayAction.MenuOpened) },
    ) {
        Item(label = "Ping the Lab (${state.pings})") { deliver(TrayAction.Ping) }
        CheckableItem(
            label = "Checkable",
            checked = state.checked,
            onCheckedChange = { deliver(TrayAction.Toggle(it)) },
        )
        Item(label = "Disabled item", isEnabled = false)
        SubMenu(label = "More") {
            Item(label = "Nested item") { deliver(TrayAction.Nested) }
        }
        Divider()
        Item(label = "Remove tray icon") { close() }
    }
}

/**
 * A classpath icon as a `file:` URI string: ComposeNativeTray parses `iconPath` through
 * `java.net.URI`, which rejects raw Windows paths (`C:\…` reads as an opaque `C:` scheme).
 */
private fun iconUri(resource: String): String =
    LabPaths.extractResource(resource, TrayViewModel::class.java).toUri().toString()
