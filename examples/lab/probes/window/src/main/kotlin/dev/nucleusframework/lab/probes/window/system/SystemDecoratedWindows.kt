package dev.nucleusframework.lab.probes.window.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.DialogWindow
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.Window
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.window.TitleBar

/**
 * A `Window` (#841): the platform draws its title bar and frame. The top-left target
 * sits at content (0, 0), so a press on it reports where the content really starts —
 * under a GTK header bar or a native caption, pointer coordinates must still be
 * content-relative.
 */
@Composable
internal fun NucleusApplicationScope.SystemDecoratedWindow(
    vm: SystemDecorationsViewModel,
    close: () -> Unit,
) {
    val state by vm.state.collectAsState()
    Window(
        onCloseRequest = close,
        state = vm.windowState,
        title = "Lab · system decorations",
    ) {
        if (state.withTitleBar) {
            // Plain app content here: no window controls, no reserved room.
            TitleBar { Text("Nucleus TitleBar — no controls expected") }
        }
        Column(
            Modifier.fillMaxSize().background(LabTheme.colors.background),
        ) {
            val density = LocalDensity.current
            Box(
                Modifier
                    .size(120.dp, 56.dp)
                    .background(LabTheme.colors.raised)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press) {
                                    val p = event.changes.first().position
                                    with(density) {
                                        vm.onIntent(
                                            SystemDecorationsIntent.TargetPressed(p.x.toDp().value, p.y.toDp().value),
                                        )
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text("Target") }
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("The title bar and frame above are the platform's own.", style = LabTheme.typography.title)
                Text("Drag, double-click, resize from the frame, and use the native buttons.")
                Text("Target presses: ${state.targetPresses}  ·  last: ${state.lastPress ?: "—"}")
                PrimaryAction("Open DialogWindow") { vm.onIntent(SystemDecorationsIntent.Open(SystemSurface.Dialog)) }
                SecondaryAction("Close") { close() }
            }
        }
    }
}

/** A `DialogWindow` (#841), owned by the window it is composed from. */
@Composable
internal fun NucleusApplicationScope.SystemDecoratedDialog(
    vm: SystemDecorationsViewModel,
    close: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = close,
        state = vm.dialogState,
        title = "Lab · system dialog",
    ) {
        Column(
            Modifier.fillMaxSize().background(LabTheme.colors.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("A DialogWindow with the platform's title bar.", style = LabTheme.typography.title)
            SecondaryAction("Close") { close() }
        }
    }
}
