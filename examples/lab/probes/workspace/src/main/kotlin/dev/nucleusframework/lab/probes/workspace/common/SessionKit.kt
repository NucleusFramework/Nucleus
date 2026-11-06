package dev.nucleusframework.lab.probes.workspace.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.style.TextOverflow
import dev.nucleusframework.lab.designsystem.ChromeIconButton
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LabIcons
import dev.nucleusframework.lab.designsystem.LabPane
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.LabWindowAppearance
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.window.DecoratedWindowScope
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.TabHoverPreview
import dev.nucleusframework.window.tao.TabHoverPreviewCard
import dev.nucleusframework.window.tao.TabWindowGroup
import dev.nucleusframework.window.tao.TabWorkspace

/**
 * Native frame and body background of a workspace window, in the Lab's style. The title
 * bar and window styles come from `LabTheme`, which the workspace bridges into every
 * window it opens.
 */
@Composable
fun DecoratedWindowScope.WorkspaceWindowFrame(content: @Composable () -> Unit) {
    LabWindowAppearance()
    LabPane(scrollable = false, padded = false) { content() }
}

/** The stock hover card, with the document's [subtitle] under the tab title. */
fun documentHoverPreview(subtitle: (tabId: String) -> String?): TabHoverPreview =
    TabHoverPreview {
        TabHoverPreviewCard(
            subtitle = {
                Text(
                    subtitle(tab.id).orEmpty(),
                    style = LabTheme.typography.small,
                    color = LabTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }

/**
 * The tab windows, mirrored through an effect: the groups are created by `Tab`, declared
 * after this list is read, and Compose drops an invalidation aimed at a scope it has just
 * composed — read straight from `workspace.groups`, a loop would never see the first window.
 */
@Composable
fun rememberTabGroups(workspace: TabWorkspace): List<TabWindowGroup> {
    var groups by remember(workspace) { mutableStateOf(workspace.groups.toList()) }
    LaunchedEffect(workspace) {
        snapshotFlow { workspace.groups.toList() }.collect { groups = it }
    }
    return groups
}

/**
 * Drops a document once the workspace has let its tab go (× on the tab, or its window
 * closed): a document still declared would be registered again and hosted nowhere.
 */
@Composable
fun DropClosedTab(
    workspace: TabWorkspace,
    id: String,
    onGone: (String) -> Unit,
) {
    val closed = workspace.tab(id) == null
    LaunchedEffect(closed) {
        if (closed) onGone(id)
    }
}

/** The "+" of a browser, shaped like the Lab's title bar buttons. */
@Composable
fun NewTabButton(onClick: () -> Unit) {
    ChromeIconButton(LabIcons.Add, "New tab", Modifier.padding(horizontal = LabDimens.gap), onClick = onClick)
}

/**
 * `TabWorkspace.tearOff` driven without a drag: the tab's window frame, nudged by
 * [offsetDp] — what a drag out of the strip produces, called directly. Returns `false`
 * while the window has no frame to measure (not realized, bridge missing).
 */
fun TabWorkspace.tearOffFromItsWindow(
    tabId: String,
    offsetDp: Float = 48f,
): Boolean {
    val window = tab(tabId)?.group?.window ?: return false
    val (left, top, right, bottom) = window.outerBoundsPx()?.takeIf { it.size >= 4 } ?: return false
    val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
    val shift = offsetDp * scale
    return tearOff(tabId, Rect(left + shift, top + shift, right + shift, bottom + shift), scale) != null
}

/**
 * Tears tabs off the most crowded window, one per step, until [windows] windows exist or
 * no window has a tab to spare. Returns how many windows there are afterwards.
 */
fun TabWorkspace.spreadInto(windows: Int): Int {
    var step = 1
    while (groups.size < windows) {
        val crowded = groups.maxByOrNull { it.ids.size } ?: break
        if (crowded.ids.size < 2) break
        if (!tearOffFromItsWindow(crowded.ids.last(), offsetDp = 48f * step++)) break
    }
    return groups.size
}

/** Everything back into the first window, in the order the windows were created. */
fun TabWorkspace.mergeAll() {
    val target = groups.firstOrNull() ?: return
    for (group in groups.drop(1).toList()) {
        for (id in group.ids) move(id, target)
    }
}

/**
 * One [SatelliteWorkspace] per tab window, keyed by group id: a tab window joins its own
 * workspace for as long as it lives, so a tab change neither creates nor destroys a palette
 * — only what the palettes draw follows the selected tab.
 */
class PerWindowSatellites {
    private val workspaces = mutableStateMapOf<String, SatelliteWorkspace>()

    val all: Map<String, SatelliteWorkspace> get() = workspaces

    fun of(groupId: String): SatelliteWorkspace = workspaces.getOrPut(groupId) { SatelliteWorkspace() }

    fun forget(groupId: String) {
        workspaces.remove(groupId)
    }
}

/** A window label built from a tab group id, for observations. */
fun windowLabel(groupId: String): String = "window $groupId"

/** Placeholder shown by a palette whose window shows no tab. */
@Composable
fun NoTabSelected(text: String = "No tab selected") {
    Box(Modifier.fillMaxSize().padding(LabDimens.block), contentAlignment = Alignment.Center) {
        EmptyState(text)
    }
}
