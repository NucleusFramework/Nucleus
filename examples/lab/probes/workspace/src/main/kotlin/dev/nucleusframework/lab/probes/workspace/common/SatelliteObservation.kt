package dev.nucleusframework.lab.probes.workspace.common

import androidx.compose.runtime.Immutable
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.TaoWindow
import kotlin.math.roundToInt

/** One satellite as its workspace reports it. */
@Immutable
data class SatelliteObservation(
    val id: String,
    val title: String,
    val isOpen: Boolean,
    val side: DockSide?,
    val order: Int?,
    /** Own docked extent in dp, when the placement carries one. */
    val extent: Int?,
    val weight: Float?,
    /** Which window hosts the docked panel, as the probe names its windows. */
    val host: String?,
    /** Offset from the owner while floating, `x, y` dp. */
    val floatingOffset: String?,
    val isActive: Boolean,
    val dockSides: Set<DockSide>,
    val floatable: Boolean,
    val reorderable: Boolean,
    val extentRange: String,
) {
    val isDocked: Boolean get() = side != null

    val placement: String
        get() =
            buildString {
                if (side != null) {
                    append("docked ${side.name.lowercase()} #$order")
                    extent?.let { append(" · ${it}dp") }
                    weight?.takeIf { it != 1f }?.let { append(" · w=${"%.2f".format(it)}") }
                    host?.let { append(" in $it") }
                } else {
                    append("floating")
                    floatingOffset?.let { append(" @ $it") }
                }
                if (!isOpen) insert(0, "closed · ")
            }
}

@Immutable
data class SatellitesObservation(
    val owner: String? = null,
    val pinned: String? = null,
    val members: Int = 0,
    val visible: Boolean = true,
    val satellites: List<SatelliteObservation> = emptyList(),
    /** Shared extent of each split side, dp. */
    val sideExtents: Map<DockSide, Int> = emptyMap(),
    val dragKind: String? = null,
    val dragged: String? = null,
    val dockPreview: String? = null,
)

/**
 * Reads the live state of [this]. [name] turns a window into the label the probe gives it
 * ("Document A", "window g2"); `null` means unknown to the probe.
 */
fun SatelliteWorkspace.observe(name: (TaoWindow?) -> String?): SatellitesObservation =
    SatellitesObservation(
        owner = owner?.let { name(it) ?: "unnamed" },
        pinned = pinnedOwner?.let { name(it) ?: "unnamed" },
        members = members.size,
        visible = visible,
        satellites =
            satellites.sortedBy { it.id }.map { entry ->
                val docked = entry.placement as? SatellitePlacement.Docked
                SatelliteObservation(
                    id = entry.id,
                    title = entry.title,
                    isOpen = entry.isOpen,
                    side = docked?.side,
                    order = docked?.order,
                    extent = docked?.extent?.value?.roundToInt(),
                    weight = docked?.weight,
                    host = if (docked != null) entry.dockHost?.let { name(it) ?: "unnamed" } else null,
                    floatingOffset =
                        if (docked == null) {
                            entry.windowState.offsetFromParent?.let {
                                "${it.x.value.roundToInt()}, ${it.y.value.roundToInt()}"
                            }
                        } else {
                            null
                        },
                    isActive = entry.windowState.isActive,
                    dockSides = entry.dockSides,
                    floatable = entry.isFloatable,
                    reorderable = entry.isReorderable,
                    extentRange = "${entry.minExtent.value.roundToInt()}..${if (entry.maxExtent.value.isInfinite()) {
                        "∞"
                    } else {
                        entry.maxExtent.value
                            .roundToInt()
                    }}",
                )
            },
        sideExtents = DockSide.entries.associateWith { dockExtent(it).value.roundToInt() },
        dragKind = dragKind?.name,
        dragged = draggedSatellite?.id,
        dockPreview =
            dockPreview?.let { target ->
                "${name(
                    target.host,
                ) ?: "unnamed"} ${target.side.name.lowercase()}${target.order?.let { " #$it" }.orEmpty()}"
            },
    )

sealed interface SatelliteChange {
    data class Opened(
        val id: String,
    ) : SatelliteChange

    data class Closed(
        val id: String,
    ) : SatelliteChange

    data class Docked(
        val id: String,
        val side: DockSide,
        val order: Int?,
        val host: String?,
    ) : SatelliteChange

    data class Undocked(
        val id: String,
    ) : SatelliteChange

    /** Still docked, on another side, rank or host. */
    data class Redocked(
        val id: String,
        val side: DockSide,
        val order: Int?,
        val host: String?,
    ) : SatelliteChange

    data class OwnerChanged(
        val owner: String?,
    ) : SatelliteChange

    data class VisibilityChanged(
        val visible: Boolean,
    ) : SatelliteChange
}

/** Structural changes only: extents and floating offsets move continuously and stay out of the timeline. */
fun diffSatellites(
    old: SatellitesObservation,
    new: SatellitesObservation,
): List<SatelliteChange> {
    val changes = mutableListOf<SatelliteChange>()
    if (old.owner != new.owner) changes += SatelliteChange.OwnerChanged(new.owner)
    if (old.visible != new.visible) changes += SatelliteChange.VisibilityChanged(new.visible)
    val before = old.satellites.associateBy { it.id }
    for (now in new.satellites) {
        val was = before[now.id] ?: continue
        if (was.isOpen !=
            now.isOpen
        ) {
            changes += if (now.isOpen) SatelliteChange.Opened(now.id) else SatelliteChange.Closed(now.id)
        }
        when {
            !was.isDocked && now.isDocked -> changes += SatelliteChange.Docked(now.id, now.side!!, now.order, now.host)
            was.isDocked && !now.isDocked -> changes += SatelliteChange.Undocked(now.id)
            now.isDocked && (was.side != now.side || was.order != now.order || was.host != now.host) ->
                changes += SatelliteChange.Redocked(now.id, now.side!!, now.order, now.host)
        }
    }
    return changes
}
