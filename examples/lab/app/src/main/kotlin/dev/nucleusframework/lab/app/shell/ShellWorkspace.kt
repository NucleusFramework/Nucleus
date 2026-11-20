package dev.nucleusframework.lab.app.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.Satellite
import dev.nucleusframework.lab.designsystem.LabIcon
import dev.nucleusframework.lab.designsystem.LabIcons
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatelliteLayoutSnapshot
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteWorkspace
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/** Where a pane lives by default, where it may dock, and its title-bar toggle. */
private val ShellPane.home: SatellitePlacement.Docked
    get() =
        when (this) {
            ShellPane.Probes -> SatellitePlacement.Docked(DockSide.Left)
            ShellPane.Checks -> SatellitePlacement.Docked(DockSide.Right)
            ShellPane.Timeline -> SatellitePlacement.Docked(DockSide.Bottom)
        }

private val ShellPane.homeExtent: Dp
    get() =
        when (this) {
            ShellPane.Probes -> 264.dp
            ShellPane.Checks -> 320.dp
            ShellPane.Timeline -> 220.dp
        }

/** How thin and how thick a pane may be docked: below, its content no longer reads. */
private val ShellPane.extentRange: ClosedRange<Dp>
    get() =
        when (this) {
            ShellPane.Probes -> 200.dp..480.dp
            ShellPane.Checks -> 240.dp..560.dp
            ShellPane.Timeline -> 120.dp..480.dp
        }

private val ShellPane.dockSides: Set<DockSide>
    get() =
        when (this) {
            ShellPane.Probes -> setOf(DockSide.Left, DockSide.Right)
            else -> setOf(DockSide.Left, DockSide.Right, DockSide.Bottom)
        }

val ShellPane.icon: LabIcon
    get() =
        when (this) {
            ShellPane.Probes -> LabIcons.ProjectTree
            ShellPane.Checks -> LabIcons.Checklist
            ShellPane.Timeline -> LabIcons.Terminal
        }

/**
 * The probe list runs the full height, the timeline spans the probe and the checks below
 * them: the shell's fixed layout before its panes became satellites.
 */
val ShellSideOrder: List<DockSide> = listOf(DockSide.Left, DockSide.Bottom, DockSide.Right, DockSide.Top)

/**
 * The shell's satellite workspace: the probe list, the checks and the timeline as satellites
 * of the main window, seeded with the layout the previous run saved (else the default one).
 * [ShellState.openPanes] decides which are open; this holder only mirrors it both ways.
 */
@Stable
class ShellWorkspace(
    saved: SatelliteLayoutSnapshot?,
) {
    val workspace = SatelliteWorkspace()

    init {
        // Before the restore, which replaces them with the saved extents.
        ShellPane.entries.forEach { workspace.setDockExtent(it.home.side, it.homeExtent) }
        saved?.let(workspace::restore)
    }

    fun snapshot(): SatelliteLayoutSnapshot = workspace.snapshot()
}

/**
 * Remembers the shell's workspace and keeps it in step with [shell]: the state's open panes
 * applied to the satellites, a pane closed from its own window or header reported back as
 * an intent, and the layout saved half a second after it last changed.
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberShellWorkspace(
    shell: ShellViewModel,
    state: ShellState,
): ShellWorkspace {
    val holder = remember(shell) { ShellWorkspace(shell.savedLayout) }
    val workspace = holder.workspace
    LaunchedEffect(holder, state.openPanes) {
        for (pane in ShellPane.entries) {
            val entry = workspace.satellite(pane.satelliteId) ?: continue
            val wanted = pane in state.openPanes
            if (entry.isOpen != wanted) {
                if (wanted) {
                    workspace.open(entry.id)
                } else {
                    workspace.close(entry.id)
                }
            }
        }
    }
    LaunchedEffect(holder) {
        snapshotFlow {
            ShellPane.entries.mapNotNull { pane -> workspace.satellite(pane.satelliteId)?.let { pane to it.isOpen } }
        }.collect { panes ->
            for ((pane, open) in panes) {
                if ((pane in shell.state.value.openPanes) != open) shell.onIntent(ShellIntent.SetPaneOpen(pane, open))
            }
        }
    }
    LaunchedEffect(holder) {
        snapshotFlow { holder.snapshot() }
            .distinctUntilChanged()
            .drop(1)
            .debounce(SAVE_DELAY_MILLIS)
            .collect { shell.saveLayout(it) }
    }
    return holder
}

/** The three panes, declared once against the shell's workspace. */
@Composable
fun NucleusApplicationScope.ShellPanes(
    holder: ShellWorkspace,
    shell: ShellViewModel,
    state: ShellState,
) {
    val current = shell.probe(state.selected).descriptor
    for (pane in ShellPane.entries) {
        key(pane) {
            Satellite(
                workspace = holder.workspace,
                id = pane.satelliteId,
                title = pane.name,
                initialPlacement = pane.home,
                initiallyOpen = pane in state.openPanes,
                dockSides = pane.dockSides,
                minExtent = pane.extentRange.start,
                maxExtent = pane.extentRange.endInclusive,
                // A pane is a tool window, not a palette: it stays when the main window fills the screen.
                hideWhileOwnerFullscreenOrMaximized = false,
                header = {
                    when (pane) {
                        ShellPane.Probes -> ShellPaneHeader(pane, "Probes", shell)
                        ShellPane.Checks ->
                            ShellPaneHeader(pane, checksTitle(current, state), shell) { ChecksActions(current, shell) }
                        ShellPane.Timeline ->
                            ShellPaneHeader(pane, timelineTitle(shell, state, current.id), shell) {
                                TimelineActions(shell, state)
                            }
                    }
                },
                floatingCaption = { PaneMoveAffordance() },
            ) {
                when (pane) {
                    ShellPane.Probes ->
                        Sidebar(shell.probes, state, { shell.onIntent(ShellIntent.Select(it)) }, Modifier.fillMaxSize())
                    ShellPane.Checks -> ChecksPanel(current, state, shell)
                    ShellPane.Timeline -> TimelinePanel(shell, state, current.id)
                }
            }
        }
    }
}

private const val SAVE_DELAY_MILLIS = 500L
