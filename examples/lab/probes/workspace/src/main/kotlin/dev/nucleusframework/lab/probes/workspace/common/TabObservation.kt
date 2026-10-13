package dev.nucleusframework.lab.probes.workspace.common

import androidx.compose.runtime.Immutable
import dev.nucleusframework.window.tao.TabWorkspace
import kotlin.math.roundToInt

/** One tab window as the workspace reports it. */
@Immutable
data class TabWindowObservation(
    val groupId: String,
    /** Strip order. */
    val tabIds: List<String>,
    val selectedId: String?,
    /** `x, y` in dp, `null` while the platform placed it. */
    val position: String?,
    val size: String,
    /** `null` until the native window exists. */
    val canPlaceOnScreen: Boolean?,
)

/** Everything a [TabWorkspace] tells about itself at one instant. */
@Immutable
data class TabsObservation(
    val windows: List<TabWindowObservation> = emptyList(),
    val titles: Map<String, String> = emptyMap(),
    val dragKind: String? = null,
    val draggedTab: String? = null,
    val dropPreview: String? = null,
    val hasThumbnails: Int = 0,
) {
    val tabCount: Int get() = windows.sumOf { it.tabIds.size }

    fun title(id: String): String = titles[id] ?: id
}

/** Reads the live state of [this]; call where snapshot reads are allowed (UI thread, `snapshotFlow`). */
fun TabWorkspace.observe(): TabsObservation =
    TabsObservation(
        windows =
            groups.map { group ->
                TabWindowObservation(
                    groupId = group.id,
                    tabIds = group.ids,
                    selectedId = group.selectedId,
                    position = group.position?.let { "${it.x.value.roundToInt()}, ${it.y.value.roundToInt()}" },
                    size = "${group.size.width.value.roundToInt()}×${group.size.height.value.roundToInt()}",
                    canPlaceOnScreen = group.window?.canPlaceOnScreen,
                )
            },
        titles = tabs.associate { it.id to it.title },
        dragKind = dragKind?.name,
        draggedTab = draggedTab?.id,
        dropPreview = dropPreview?.let { "${it.group.id} @ ${it.index}" },
        hasThumbnails = tabs.count { it.thumbnail != null },
    )

/** A change between two observations, worded for the timeline. */
sealed interface TabChange {
    data class Opened(
        val tab: String,
        val group: String,
    ) : TabChange

    data class Closed(
        val tab: String,
    ) : TabChange

    /** A tab left its strip for a window that did not exist before. */
    data class TornOff(
        val tab: String,
        val from: String,
        val into: String,
    ) : TabChange

    /** A window whose only tab joined another strip, and closed. */
    data class Merged(
        val tab: String,
        val window: String,
        val into: String,
    ) : TabChange

    data class Moved(
        val tab: String,
        val from: String,
        val into: String,
        val index: Int,
    ) : TabChange

    data class Reordered(
        val group: String,
        val before: List<String>,
        val after: List<String>,
    ) : TabChange

    data class Selected(
        val tab: String,
        val group: String,
    ) : TabChange

    data class WindowOpened(
        val group: String,
    ) : TabChange

    data class WindowClosed(
        val group: String,
    ) : TabChange
}

/** What turned [old] into [new]; empty when nothing structural changed. */
fun diffTabs(
    old: TabsObservation,
    new: TabsObservation,
): List<TabChange> {
    val oldGroups = old.windows.associateBy { it.groupId }
    val newGroups = new.windows.associateBy { it.groupId }
    val oldGroupOf = old.windows.flatMap { w -> w.tabIds.map { it to w.groupId } }.toMap()
    val newGroupOf = new.windows.flatMap { w -> w.tabIds.map { it to w.groupId } }.toMap()
    val changes = mutableListOf<TabChange>()
    val explainedOpened = mutableSetOf<String>()
    val explainedClosed = mutableSetOf<String>()
    val movedTabs = mutableSetOf<String>()

    for ((tab, group) in newGroupOf) {
        val before = oldGroupOf[tab]
        when {
            before == null -> changes += TabChange.Opened(tab, group)
            before == group -> Unit
            group !in oldGroups -> {
                changes += TabChange.TornOff(tab, before, group)
                explainedOpened += group
                movedTabs += tab
            }
            before !in newGroups && oldGroups.getValue(before).tabIds.size == 1 -> {
                changes += TabChange.Merged(tab, before, group)
                explainedClosed += before
                movedTabs += tab
            }
            else -> {
                changes += TabChange.Moved(tab, before, group, newGroups.getValue(group).tabIds.indexOf(tab))
                movedTabs += tab
            }
        }
    }
    for (tab in oldGroupOf.keys - newGroupOf.keys) changes += TabChange.Closed(tab)

    for ((id, now) in newGroups) {
        val was = oldGroups[id] ?: continue
        // Compare the order of the tabs that stayed, so a tab leaving or arriving is no reorder.
        val stayed = was.tabIds.filter { it in now.tabIds }
        val stayedNow = now.tabIds.filter { it in was.tabIds }
        if (stayed != stayedNow) changes += TabChange.Reordered(id, stayed, stayedNow)
        val selected = now.selectedId
        if (selected != null && selected != was.selectedId && selected !in movedTabs && oldGroupOf[selected] != null) {
            changes += TabChange.Selected(selected, id)
        }
    }
    for (id in newGroups.keys - oldGroups.keys - explainedOpened) changes += TabChange.WindowOpened(id)
    for (id in oldGroups.keys - newGroups.keys - explainedClosed) changes += TabChange.WindowClosed(id)
    return changes
}
