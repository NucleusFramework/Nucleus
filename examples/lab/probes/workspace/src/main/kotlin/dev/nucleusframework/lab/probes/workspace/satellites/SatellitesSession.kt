package dev.nucleusframework.lab.probes.workspace.satellites

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.Satellite
import dev.nucleusframework.lab.designsystem.Divider
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LabDecoratedWindow
import dev.nucleusframework.lab.designsystem.LabPane
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.LabTitle
import dev.nucleusframework.lab.designsystem.LabTitleBar
import dev.nucleusframework.lab.designsystem.LabWindowAppearance
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.window.tao.DockLayout
import dev.nucleusframework.window.tao.JoinSatelliteWorkspace

/**
 * Two document windows joining one workspace, and the Inspector and Tools declared once
 * against it: floating, they belong to the document focused last (or the pinned one) and
 * follow it; docked, they are panels of a document's `DockLayout`. Closing Document A
 * ends the session; closing B only drops it.
 */
@Composable
fun NucleusApplicationScope.SatellitesSession(
    model: SatellitesSessionModel,
    close: () -> Unit,
) {
    DocumentWindow(model, DocumentId.A, WindowPosition(80.dp, 80.dp), onCloseRequest = close)
    if (model.live.secondDocument) {
        DocumentWindow(model, DocumentId.B, WindowPosition(860.dp, 80.dp)) {
            model.live = model.live.copy(secondDocument = false)
        }
    }
    val options = model.options
    Satellite(
        workspace = model.workspace,
        id = SatellitesSessionModel.INSPECTOR_ID,
        title = "Inspector",
        initialPlacement = model.inspectorPlacement,
        dockSides = options.inspectorSides.sides,
        resizable = options.inspectorResizable,
        hideWhileOwnerFullscreenOrMaximized = model.live.hideWhileOwnerFills,
    ) {
        InspectorContent(model)
    }
    Satellite(
        workspace = model.workspace,
        id = SatellitesSessionModel.TOOLS_ID,
        title = "Tools",
        initialPlacement = model.toolsPlacement,
        floatable = options.toolsFloatable,
        reorderable = options.toolsReorderable,
        hideWhileOwnerFullscreenOrMaximized = model.live.hideWhileOwnerFills,
    ) {
        ToolsContent()
    }
}

@Composable
private fun NucleusApplicationScope.DocumentWindow(
    model: SatellitesSessionModel,
    id: DocumentId,
    position: WindowPosition,
    onCloseRequest: () -> Unit,
) {
    LabDecoratedWindow(
        title = id.title,
        onCloseRequest = onCloseRequest,
        state = rememberWindowState(width = 720.dp, height = 640.dp, position = position),
        minimumSize = DpSize(480.dp, 420.dp),
    ) {
        // A member for as long as the window lives: a candidate owner and a dock host.
        JoinSatelliteWorkspace(model.workspace)
        val window = nucleusWindow
        DisposableEffect(window) {
            model.publish(id, window)
            onDispose { model.forget(id) }
        }
        LabWindowAppearance()
        Column(Modifier.fillMaxSize()) {
            LabTitleBar { LabTitle(id.title) }
            Divider()
            DockLayout(model.workspace, Modifier.weight(1f).fillMaxWidth()) {
                DocumentBody(model, id)
            }
        }
    }
}

@Composable
private fun DocumentBody(
    model: SatellitesSessionModel,
    id: DocumentId,
) {
    val workspace = model.workspace
    LabPane {
        Text(id.title, style = LabTheme.typography.title)
        Hint(
            "Drag a satellite by its header toward this window's edges: the zones light up, release to dock. " +
                "Drag a docked panel's header out over the document to float it again, state intact. " +
                "Every other control is in the Lab.",
        )
        SubHeading("As this window sees it")
        Readout("owner", model.document(workspace.owner)?.title)
        Readout("owns the floating satellites", (model.document(workspace.owner) == id).toString())
        Readout(
            "panels docked here",
            workspace.satellites.count { model.document(it.dockHost) == id && it.isDocked }.toString(),
        )
    }
}
