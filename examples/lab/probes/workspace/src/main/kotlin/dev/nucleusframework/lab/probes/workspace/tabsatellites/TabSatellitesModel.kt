package dev.nucleusframework.lab.probes.workspace.tabsatellites

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.probes.workspace.common.ComposedModel
import dev.nucleusframework.lab.probes.workspace.common.Document
import dev.nucleusframework.lab.probes.workspace.common.DocumentSet
import dev.nucleusframework.lab.probes.workspace.common.PerWindowSatellites
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.TabWorkspace
import dev.nucleusframework.window.tao.WindowAnchor
import dev.nucleusframework.window.tao.WindowConstraintAdjustment
import dev.nucleusframework.window.tao.WindowPositioner

/** The kinds of satellite a document can ask for. */
enum class SatelliteKind(
    val label: String,
) {
    Inspector("Inspector"),
    Palette("Palette"),
    ;

    /** This kind's entry id in the workspace of tab window [groupId]. */
    fun idIn(groupId: String): String = "$groupId-${name.lowercase()}"

    val placement: SatellitePlacement
        get() =
            when (this) {
                Inspector ->
                    SatellitePlacement.Floating(
                        positioner =
                            WindowPositioner(
                                parentAnchor = WindowAnchor.Right,
                                childAnchor = WindowAnchor.Left,
                                offset = DpOffset(12.dp, 0.dp),
                                constraintAdjustment = WindowConstraintAdjustment.FlipAndSlide,
                            ),
                        size = DpSize(300.dp, 360.dp),
                    )
                Palette -> SatellitePlacement.Docked(DockSide.Left)
            }
}

/** What a document asks of its window's satellites. */
@Immutable
data class Wants(
    val kinds: Set<SatelliteKind>,
    val accent: Color,
)

/** The values the palettes edit: the document's, so they outlive every window and panel. */
class DocumentValues {
    var strength by mutableFloatStateOf(0.4f)
    var edits by mutableIntStateOf(0)
    var swatch by mutableIntStateOf(0)
}

@Immutable
data class TabSatellitesLive(
    /** On: each selected document opens the palettes it asks for. Off: every palette stays open. */
    val documentsDecide: Boolean = true,
)

/**
 * Tabs whose windows each get a satellite workspace of their own. Membership is per
 * window, so switching tabs creates and destroys no palette; only the content they draw
 * (the window's selected tab) and which of them are open follow the document.
 */
class TabSatellitesModel(
    live: TabSatellitesLive,
) : ComposedModel<TabSatellitesLive> {
    override val tabs = TabWorkspace(defaultWindowSize = DpSize(860.dp, 620.dp))
    override val docks = PerWindowSatellites()
    override var live: TabSatellitesLive by mutableStateOf(live)

    val documents =
        DocumentSet(
            seed =
                listOf(
                    Document(
                        "scene",
                        "Scene.kt",
                        "asks for both",
                        "",
                        Wants(SatelliteKind.entries.toSet(), Color(0xFF7AA2F7)),
                    ),
                    Document(
                        "shader",
                        "Shader.glsl",
                        "asks for the Inspector",
                        "",
                        Wants(setOf(SatelliteKind.Inspector), Color(0xFF9ECE6A)),
                    ),
                    Document("notes", "notes.md", "asks for none", "", Wants(emptySet(), Color(0xFFE0AF68))),
                ),
            make = { n ->
                val kinds = if (n % 2 == 0) setOf(SatelliteKind.Palette) else SatelliteKind.entries.toSet()
                Document(
                    "draft-$n",
                    "draft$n.kt",
                    "asks for ${kinds.joinToString { it.label }}",
                    "",
                    Wants(
                        kinds,
                        DraftAccents[
                            n %
                                DraftAccents.size,
                        ],
                    ),
                )
            },
        )

    private val values = mutableStateMapOf<String, DocumentValues>()

    fun valuesOf(documentId: String): DocumentValues = values.getOrPut(documentId) { DocumentValues() }

    fun forget(documentId: String) {
        documents.forget(documentId)
        values.remove(documentId)
    }

    override fun openDocument() {
        documents.open()
    }

    override fun resetDock(groupId: String) {
        val workspace = docks.of(groupId)
        for (kind in SatelliteKind.entries) {
            when (val placement = kind.placement) {
                is SatellitePlacement.Docked ->
                    workspace.dock(
                        kind.idIn(groupId),
                        placement.side,
                        order = placement.order,
                    )
                is SatellitePlacement.Floating -> workspace.undock(kind.idIn(groupId))
            }
        }
    }

    private companion object {
        val DraftAccents = listOf(Color(0xFFBB9AF7), Color(0xFF7DCFFF), Color(0xFFF7768E), Color(0xFF73DACA))
    }
}
