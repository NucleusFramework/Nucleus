package dev.nucleusframework.tabsatellitesdemo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.TabLayoutSnapshot
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

    /** Id of this kind's entry in the workspace of the tab window [groupId]. */
    fun idIn(groupId: String): String = "$groupId-${name.lowercase()}"
}

/**
 * One document of the demo: one tab, and the satellites it asks for.
 *
 * No document is *obliged* to have any. The entries are declared per tab window
 * so that switching tabs creates and destroys nothing, and each one is opened
 * or closed to match the selected document — so a document with no palettes
 * shows none, and one with a single palette shows one.
 *
 * @property id the tab's identity.
 * @property title shown on the tab and, while it is selected, as the window title.
 * @property accent the colour its palette starts on, so each document is
 *   recognisable at a glance whichever window it ends up in.
 * @property satellites which palettes this document wants; empty is a document
 *   with none.
 */
class Document(
    val id: String,
    val title: String,
    val accent: Color,
    val satellites: Set<SatelliteKind>,
)

/**
 * The values a document's palettes edit, kept here rather than in the palettes:
 * they belong to the document, so they have to outlive any window or panel it
 * is shown in — and be there unchanged when its tab comes back into view.
 */
class DocumentState {
    var strength: Float by mutableStateOf(INITIAL_STRENGTH)
    var edits: Int by mutableStateOf(0)
    var swatch: Int by mutableStateOf(0)

    private companion object {
        const val INITIAL_STRENGTH = 0.4f
    }
}

/**
 * Everything the demo drives.
 *
 * [tabs] is the one tab workspace: it owns which windows exist and which tab
 * each window shows. [satellitesOfWindow] hands out one [SatelliteWorkspace]
 * **per tab window**, and that is the whole trick:
 *
 *  - a tab window joins its own workspace once, for as long as the window
 *    lives, so the palettes exist exactly as long as the window does. Switching
 *    tabs inside it neither creates nor destroys anything — tying membership to
 *    the *tab body* instead means a native palette window is destroyed and
 *    another created on every switch, which flashes;
 *  - what follows the tab is the palettes' **content**: they show the window's
 *    selected tab, and the per-document values live in [stateOf], outside
 *    composition, so each document brings its own back;
 *  - a tab torn into a window of its own gets that window's palettes, and two
 *    windows showing two tabs show two independent sets at the same time.
 */
class DemoState {
    val tabs = TabWorkspace(defaultWindowSize = DpSize(WINDOW_WIDTH_DP.dp, WINDOW_HEIGHT_DP.dp))

    /** The open documents, in declaration order. One tab each. */
    val documents =
        mutableStateListOf(
            // Both palettes, one, and none: a document decides.
            Document("scene", "Scene.kt", Color(0xFF7AA2F7), setOf(SatelliteKind.Inspector, SatelliteKind.Palette)),
            Document("shader", "Shader.glsl", Color(0xFF9ECE6A), setOf(SatelliteKind.Inspector)),
            Document("notes", "notes.md", Color(0xFFE0AF68), emptySet()),
        )

    private val workspaces = mutableStateMapOf<String, SatelliteWorkspace>()
    private val documentStates = mutableStateMapOf<String, DocumentState>()

    /**
     * The satellite workspace of the tab window [groupId], created the first
     * time it is asked for and dropped with the window ([forgetWindow]).
     */
    fun satellitesOfWindow(groupId: String): SatelliteWorkspace =
        workspaces.getOrPut(groupId) {
            // followFocus is beside the point with a single member: the tab
            // window is the only candidate owner this workspace ever has.
            SatelliteWorkspace()
        }

    /** Drops the workspace of a tab window that is gone. */
    fun forgetWindow(groupId: String) {
        workspaces.remove(groupId)
    }

    /** The palette values of [documentId], created the first time they are asked for. */
    fun stateOf(documentId: String): DocumentState = documentStates.getOrPut(documentId) { DocumentState() }

    /** The document [id] names, or `null` once it has been closed. */
    fun document(id: String): Document? = documents.firstOrNull { it.id == id }

    /** The layout captured by "Save tab layout", ready for "Restore". */
    var savedLayout: TabLayoutSnapshot? by mutableStateOf(null)
        private set

    private var opened = 0

    /**
     * Opens a new document; its tab lands in the window focused last. Drafts
     * alternate between "a palette only" and "both", so the difference between
     * documents is visible without editing any code.
     */
    fun open() {
        opened++
        val wants =
            if (opened % 2 == 0) {
                setOf(SatelliteKind.Palette)
            } else {
                setOf(SatelliteKind.Inspector, SatelliteKind.Palette)
            }
        documents += Document("draft-$opened", "draft$opened.kt", DraftAccents[opened % DraftAccents.size], wants)
    }

    /** Drops the document [id] — and the values it owned — once its tab is gone. */
    fun forget(id: String) {
        documents.removeAll { it.id == id }
        documentStates.remove(id)
    }

    fun saveLayout() {
        savedLayout = tabs.snapshot()
    }

    fun restoreLayout() {
        savedLayout?.let(tabs::restore)
    }

    /**
     * The placement a kind starts in: the inspector floats off the window's
     * right edge, the palette starts docked on its left.
     */
    fun placementOf(kind: SatelliteKind): SatellitePlacement =
        when (kind) {
            SatelliteKind.Inspector -> InspectorPlacement
            SatelliteKind.Palette -> PalettePlacement
        }

    companion object {
        /** The inspector floats off the right edge of whichever window holds the tab. */
        val InspectorPlacement: SatellitePlacement
            get() =
                SatellitePlacement.Floating(
                    positioner =
                        WindowPositioner(
                            parentAnchor = WindowAnchor.Right,
                            childAnchor = WindowAnchor.Left,
                            offset = DpOffset(GAP_DP.dp, 0.dp),
                            constraintAdjustment = WindowConstraintAdjustment.FlipAndSlide,
                        ),
                    size = DpSize(INSPECTOR_W_DP.dp, INSPECTOR_H_DP.dp),
                )

        /** The palette starts docked, so the composition of the two archetypes shows on first launch. */
        val PalettePlacement: SatellitePlacement get() = SatellitePlacement.Docked(DockSide.Left)

        private val DraftAccents =
            listOf(
                Color(0xFFBB9AF7),
                Color(0xFF7DCFFF),
                Color(0xFFF7768E),
                Color(0xFF73DACA),
            )

        private const val WINDOW_WIDTH_DP = 860
        private const val WINDOW_HEIGHT_DP = 620
        private const val INSPECTOR_W_DP = 300
        private const val INSPECTOR_H_DP = 360
        private const val GAP_DP = 12
    }
}
