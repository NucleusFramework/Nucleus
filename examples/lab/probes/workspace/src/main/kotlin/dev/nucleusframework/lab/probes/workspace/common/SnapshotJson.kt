package dev.nucleusframework.lab.probes.workspace.common

import dev.nucleusframework.window.tao.SatelliteLayoutSnapshot
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.TabLayoutSnapshot
import kotlin.math.roundToInt

/**
 * The snapshots are "serializable by the app": this is what an app would persist, written
 * out so the tester sees exactly what a save captured and how big it is.
 */
fun TabLayoutSnapshot.toJson(): String =
    groups.joinToString(",\n", prefix = "{\"groups\":[\n", postfix = "\n]}") { group ->
        buildString {
            append("  {\"id\":${group.id.quoted()},\"tabs\":[")
            append(group.tabIds.joinToString(",") { it.quoted() })
            append("],\"selected\":${group.selectedId?.quoted() ?: "null"}")
            append(
                ",\"position\":${group.position?.let {
                    "[${it.x.value.roundToInt()},${it.y.value.roundToInt()}]"
                } ?: "null"}",
            )
            append(",\"size\":[${group.size.width.value.roundToInt()},${group.size.height.value.roundToInt()}]}")
        }
    }

fun SatelliteLayoutSnapshot.toJson(): String =
    buildString {
        append("{\"satellites\":{\n")
        append(
            satellites.entries.sortedBy { it.key }.joinToString(",\n") { (id, snapshot) ->
                val placement =
                    when (val p = snapshot.placement) {
                        is SatellitePlacement.Docked ->
                            "{\"docked\":${p.side.name.quoted()},\"order\":${p.order}" +
                                ",\"extent\":${p.extent?.value?.roundToInt() ?: "null"},\"weight\":${p.weight}}"
                        is SatellitePlacement.Floating ->
                            "{\"floating\":[${p.size.width.value.roundToInt()},${p.size.height.value.roundToInt()}]" +
                                ",\"offset\":[${p.positioner.offset.x.value.roundToInt()},${p.positioner.offset.y.value.roundToInt()}]}"
                    }
                "  ${id.quoted()}:{\"open\":${snapshot.isOpen},\"placement\":$placement}"
            },
        )
        append("\n},\"dockExtents\":{")
        append(
            dockExtents.entries
                .sortedBy {
                    it.key
                }.joinToString(",") { (side, dp) -> "${side.name.quoted()}:${dp.value.roundToInt()}" },
        )
        append("}}")
    }

private fun String.quoted(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
