package dev.nucleusframework.lab.probes.window.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LabWindowFrame
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.probes.window.shared.describe

/**
 * The session window: a plain decorated window driven entirely by [vm]. Its flags (dock,
 * stacking, size limits, the v2 state) are more than `LabSessionWindow` takes, so it is a
 * `DecoratedWindow` in the Lab's frame.
 */
@Composable
internal fun NucleusApplicationScope.StateLabWindow(
    vm: StateLabViewModel,
    close: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val flags = state.flags
    // hiddenFromDock is creation-time: a new value means a new native window.
    key(flags.hiddenFromDock) {
        DecoratedWindow(
            onCloseRequest = close,
            state = vm.windowState,
            title = flags.title,
            resizable = flags.resizable,
            minimizable = flags.minimizable,
            maximizable = flags.maximizable,
            enabled = flags.enabled,
            focusable = flags.focusable,
            alwaysOnTop = flags.alwaysOnTop,
            alwaysOnBottom = flags.alwaysOnBottom,
            hiddenFromDock = flags.hiddenFromDock,
            visibleOnAllWorkspaces = flags.visibleOnAllWorkspaces,
            minSize = if (flags.minSize) DpSize(480.dp, 320.dp) else DpSize.Unspecified,
            maxSize = if (flags.maxSize) DpSize(1000.dp, 700.dp) else DpSize.Unspecified,
        ) {
            val tao = nucleusWindow.unsafe.taoWindow
            LaunchedEffect(tao) { tao?.let { vm.onIntent(StateLabIntent.Attached(it)) } }
            LabWindowFrame(flags.title, "driven from the Lab · Window state & geometry") {
                Hint("Every change here is requested from the Lab and read back there.")
                Readout("Outer bounds", state.snapshot?.outerPx?.describe())
                Readout("v2 bounds", state.v2?.boundsDp)
                Readout("Monitor", state.snapshot?.monitor)
                Readout("Focused", state.snapshot?.focused?.toString())
                Hint("Drag the title bar, resize the edges, use the OS shortcuts: the Lab follows.")
            }
        }
    }
}
