package dev.nucleusframework.tabsatellitesdemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.Satellite
import dev.nucleusframework.application.Tab
import dev.nucleusframework.application.TabWindows
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.material.rememberMaterialTitleBarStyle
import dev.nucleusframework.window.material.rememberMaterialWindowStyle
import dev.nucleusframework.window.styling.LocalDecoratedWindowStyle
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.tao.JoinSatelliteWorkspace
import dev.nucleusframework.window.tao.TabWindowGroup
import dev.nucleusframework.window.tao.TabWorkspace

private val DemoDarkColors =
    darkColorScheme(
        primary = Color(0xFF8AA4FF),
        surface = Color(0xFF15171C),
        surfaceContainer = Color(0xFF1C1F26),
        surfaceContainerHigh = Color(0xFF232730),
        background = Color(0xFF101216),
    )

private val DemoLightColors =
    lightColorScheme(
        primary = Color(0xFF3F5DDB),
        surface = Color(0xFFF7F8FB),
        surfaceContainer = Color(0xFFEDEFF5),
        surfaceContainerHigh = Color(0xFFE4E7EF),
        background = Color(0xFFFBFCFE),
    )

/**
 * Chrome-like tabs where every tab has its satellites.
 *
 * `TabWindows` owns the windows and `Tab` declares the documents, as in
 * `examples/tabs-demo`. On top of that, each **tab window** gets a
 * `SatelliteWorkspace` of its own with an Inspector and a Palette, and those
 * palettes show the window's selected tab: switch tabs and their content
 * changes, tear a tab into a window of its own and it arrives with palettes of
 * its own, so two windows show two independent sets at once.
 *
 * Why the workspace is per window and not per document: a satellite exists for
 * as long as its entry is declared *and* its workspace has an owner. Hanging
 * either of those on the selected tab means a native palette window is
 * destroyed and a new one created on every tab change — visible as a flash.
 * Per window, nothing is created or destroyed by a tab change at all; only the
 * content the palettes draw changes, and the per-document values behind it live
 * in [DemoState.stateOf].
 */
fun main() =
    nucleusApplication {
        val demo = remember { DemoState() }
        val dark = isSystemInDarkMode()
        val colors = if (dark) DemoDarkColors else DemoLightColors

        DemoTheme(colors) {
            TabWindows(
                workspace = demo.tabs,
                strip = { DemoTabStrip(onNewTab = demo::open) },
                windowWrapper = { content ->
                    WindowBackground(colors.background)
                    WindowAppearance(if (dark) WindowAppearanceMode.Dark else WindowAppearanceMode.Light)
                    // This window joins its own satellite workspace, once, for
                    // as long as it lives — which is what keeps a tab change
                    // from touching the palettes at all.
                    val group = demo.tabs.groupOf(nucleusWindow.unsafe.taoWindow)
                    if (group != null) JoinSatelliteWorkspace(demo.satellitesOfWindow(group.id))
                    Surface(Modifier.fillMaxSize(), color = colors.background) { content() }
                },
                onLastWindowClosed = ::exitApplication,
            )

            for (document in demo.documents) {
                key(document.id) {
                    Tab(demo.tabs, id = document.id, title = document.title) {
                        DocumentContent(demo, document)
                    }
                    DropClosedTab(demo, document.id)
                }
            }

            // One set of satellites per tab window, declared at application
            // scope so they are not tied to whichever tab is showing.
            for (group in rememberTabGroups(demo.tabs)) {
                key(group.id) { WindowSatellites(demo, group) }
            }
        }
    }

/**
 * The tab windows, mirrored out of the workspace through an effect.
 *
 * The groups are created by `Tab`, which is declared above this call, so the
 * write that adds one lands during the composition that has already read the
 * list — and Compose drops an invalidation aimed at a scope it has just
 * composed. Read straight from `workspace.groups`, this loop would never see
 * the first window. `TabWindows` mirrors the list for exactly the same reason.
 */
@Composable
private fun rememberTabGroups(workspace: TabWorkspace): List<TabWindowGroup> {
    var groups by remember(workspace) { mutableStateOf(workspace.groups.toList()) }
    LaunchedEffect(workspace) {
        snapshotFlow { workspace.groups.toList() }.collect { groups = it }
    }
    return groups
}

/**
 * The satellites of one tab window: one entry per [SatelliteKind], declared
 * against that window's workspace and drawing whichever tab the window is
 * showing.
 *
 * The entries are per window so that a tab change creates and destroys nothing.
 * Which of them are *open* is per document: a document that asks for no
 * palettes shows none, one that asks for a single palette shows one. That does
 * mean a palette genuinely appears or disappears when you move between
 * documents that disagree about it — which is the point, and is not the same
 * thing as every switch churning every palette.
 */
@Composable
private fun WindowSatellites(
    demo: DemoState,
    group: TabWindowGroup,
) {
    val workspace = demo.satellitesOfWindow(group.id)
    DisposableEffect(demo, group.id) {
        onDispose { demo.forgetWindow(group.id) }
    }

    // The selected tab of *this* window, resolved back to the document. The
    // entry id is the document id, which is what ties the two archetypes
    // together without either knowing about the other.
    val document = demo.tabs.selectedTab(group)?.let { demo.document(it.id) }
    val suffix = document?.let { " — ${it.title}" }.orEmpty()

    for (kind in SatelliteKind.entries) {
        Satellite(
            workspace = workspace,
            id = kind.idIn(group.id),
            title = "${kind.label}$suffix",
            initialPlacement = demo.placementOf(kind),
            // Closed until a document asks for it: the effect below is what
            // decides, and it only runs once the entry exists.
            initiallyOpen = false,
        ) {
            // A title change is just state on the entry; only a change of *id*
            // would swap the entry — and with it the window it is composed in.
            if (document == null) NoTabSelected() else KindContent(kind, demo, document)
        }
    }

    // Match the open entries to what the selected document asks for. From an
    // effect, never during composition: `open` / `close` write workspace state,
    // and a write mid-composition is exactly what the tab workspace had to be
    // taught to survive.
    LaunchedEffect(workspace, group.id, document?.id) {
        val wanted = document?.satellites.orEmpty()
        for (kind in SatelliteKind.entries) {
            val id = kind.idIn(group.id)
            if (kind in wanted) workspace.open(id) else workspace.close(id)
        }
    }
}

/** The body of one kind of satellite, for the document it is drawing. */
@Composable
private fun dev.nucleusframework.window.tao.SatelliteScope.KindContent(
    kind: SatelliteKind,
    demo: DemoState,
    document: Document,
) {
    when (kind) {
        SatelliteKind.Inspector -> InspectorContent(demo, document)
        SatelliteKind.Palette -> PaletteContent(demo, document)
    }
}

/** What a palette shows for a window that has no selected tab — a frame at most. */
@Composable
private fun NoTabSelected() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Text("No tab selected", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Keeps the document list in step with the tab workspace: closing a tab is a
 * workspace call, and a document still declared once its tab is gone would be
 * registered again and hosted nowhere.
 */
@Composable
private fun DropClosedTab(
    demo: DemoState,
    id: String,
) {
    val closed = demo.tabs.tab(id) == null
    LaunchedEffect(closed) {
        if (closed) demo.forget(id)
    }
}

/**
 * Material colours plus the window-chrome styles derived from them.
 *
 * Established once, above the windows: the workspace opens and closes them, and
 * these locals are bridged into every scene it creates — the tab strips in the
 * title bars and the floating satellites' own scenes included.
 */
@Composable
private fun DemoTheme(
    colors: ColorScheme,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(
            LocalTitleBarStyle provides rememberMaterialTitleBarStyle(colors),
            LocalDecoratedWindowStyle provides rememberMaterialWindowStyle(colors),
            content = content,
        )
    }
}
