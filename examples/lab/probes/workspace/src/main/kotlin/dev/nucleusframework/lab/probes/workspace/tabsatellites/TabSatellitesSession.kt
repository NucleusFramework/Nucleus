package dev.nucleusframework.lab.probes.workspace.tabsatellites

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.Satellite
import dev.nucleusframework.application.Tab
import dev.nucleusframework.application.TabWindows
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ColorSwatch
import dev.nucleusframework.lab.designsystem.LabPane
import dev.nucleusframework.lab.designsystem.LabSlider
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.probes.workspace.common.Document
import dev.nucleusframework.lab.probes.workspace.common.DropClosedTab
import dev.nucleusframework.lab.probes.workspace.common.NewTabButton
import dev.nucleusframework.lab.probes.workspace.common.NoTabSelected
import dev.nucleusframework.lab.probes.workspace.common.SaveableTabBody
import dev.nucleusframework.lab.probes.workspace.common.WorkspaceWindowFrame
import dev.nucleusframework.lab.probes.workspace.common.rememberTabGroups
import dev.nucleusframework.window.tao.DockLayout
import dev.nucleusframework.window.tao.JoinSatelliteWorkspace
import dev.nucleusframework.window.tao.SatelliteScope
import dev.nucleusframework.window.tao.TabStrip
import dev.nucleusframework.window.tao.TabWindowGroup
import kotlin.math.roundToInt

@Composable
fun NucleusApplicationScope.TabSatellitesSession(
    model: TabSatellitesModel,
    close: () -> Unit,
) {
    TabWindows(
        workspace = model.tabs,
        strip = { TabStrip(trailing = { NewTabButton(model::openDocument) }) },
        windowWrapper = { content ->
            WorkspaceWindowFrame {
                // Joined once per window, for as long as it lives: a tab change touches no palette.
                model.tabs
                    .groupOf(
                        nucleusWindow.unsafe.taoWindow,
                    )?.let { JoinSatelliteWorkspace(model.docks.of(it.id)) }
                content()
            }
        },
        // The dock is window chrome: hung under the strip, so a tab change rebuilds none of it.
        windowBodyWrapper = { body ->
            val group = model.tabs.groupOf(nucleusWindow.unsafe.taoWindow)
            if (group == null) body() else DockLayout(model.docks.of(group.id), Modifier.fillMaxSize()) { body() }
        },
        onLastWindowClosed = close,
    )
    for (document in model.documents.documents) {
        key(document.id) {
            Tab(model.tabs, id = document.id, title = document.title) {
                SaveableTabBody(document, onNewTab = model::openDocument) {
                    Readout(
                        "asks for",
                        document.extra.kinds
                            .joinToString { it.label }
                            .ifEmpty { "no satellite" },
                    )
                }
            }
            DropClosedTab(model.tabs, document.id, model::forget)
        }
    }
    for (group in rememberTabGroups(model.tabs)) {
        key(group.id) { WindowSatellites(model, group) }
    }
}

/** One entry per kind, declared against this window's workspace, drawing its selected tab. */
@Composable
private fun NucleusApplicationScope.WindowSatellites(
    model: TabSatellitesModel,
    group: TabWindowGroup,
) {
    val workspace = model.docks.of(group.id)
    DisposableEffect(model, group.id) { onDispose { model.docks.forget(group.id) } }
    val document = model.tabs.selectedTab(group)?.let { model.documents.document(it.id) }
    for (kind in SatelliteKind.entries) {
        Satellite(
            workspace = workspace,
            id = kind.idIn(group.id),
            // A title change is state on the entry; only a new id would swap the window.
            title = kind.label + document?.let { " — ${it.title}" }.orEmpty(),
            initialPlacement = kind.placement,
            initiallyOpen = false,
        ) {
            if (document == null) NoTabSelected() else KindContent(kind, model, document)
        }
    }
    // From an effect, never mid-composition: open / close write workspace state.
    LaunchedEffect(workspace, group.id, document?.id, model.live.documentsDecide) {
        val wanted = if (model.live.documentsDecide) document?.extra?.kinds.orEmpty() else SatelliteKind.entries.toSet()
        for (kind in SatelliteKind.entries) {
            if (kind in wanted) workspace.open(kind.idIn(group.id)) else workspace.close(kind.idIn(group.id))
        }
    }
}

@Composable
private fun SatelliteScope.KindContent(
    kind: SatelliteKind,
    model: TabSatellitesModel,
    document: Document<Wants>,
) {
    val values = model.valuesOf(document.id)
    LabPane {
        Text("${kind.label} — ${document.title}", style = LabTheme.typography.heading)
        when (kind) {
            SatelliteKind.Inspector -> {
                Readout("strength", "${(values.strength * 100).roundToInt()}%")
                LabSlider(values.strength, Modifier.fillMaxWidth()) { values.strength = it }
                Actions { SecondaryAction("edits: ${values.edits}") { values.edits++ } }
            }
            SatelliteKind.Palette ->
                Actions {
                    SwatchAlphas.forEachIndexed { index, alpha ->
                        ColorSwatch(
                            document.extra.accent.copy(alpha = alpha),
                            selected = index == values.swatch,
                            onClick = { values.swatch = index },
                        )
                    }
                }
        }
        Readout("hosted as", if (isDocked) "docked panel" else "floating window")
        Readout("values belong to", document.title)
    }
}

private val SwatchAlphas = listOf(1f, 0.75f, 0.5f, 0.3f)
