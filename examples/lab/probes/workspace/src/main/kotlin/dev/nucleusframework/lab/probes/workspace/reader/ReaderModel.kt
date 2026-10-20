package dev.nucleusframework.lab.probes.workspace.reader

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.probes.workspace.common.ComposedModel
import dev.nucleusframework.lab.probes.workspace.common.Document
import dev.nucleusframework.lab.probes.workspace.common.DocumentSet
import dev.nucleusframework.lab.probes.workspace.common.PerWindowSatellites
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.TabWorkspace

enum class ReaderStyle { Classic, Islands }

/** Anywhere but the top: the reader's top is its tab strip. */
val ReaderDockSides: Set<DockSide> = setOf(DockSide.Left, DockSide.Right, DockSide.Bottom)

/**
 * One pane of the reader. [fixed] is furniture — the library and the contents live on
 * the right of the text, in that order: `floatable = false`, `reorderable = false`,
 * `dockSides = {Right}`. They can still be hidden and resized.
 */
enum class Pane(
    val id: String,
    val title: String,
    val home: SatellitePlacement.Docked,
    val openAtStart: Boolean,
    val fixed: Boolean = false,
) {
    Library(
        "library",
        "Library",
        SatellitePlacement.Docked(DockSide.Right, order = 0, extent = 200.dp),
        openAtStart = true,
        fixed = true,
    ),
    Contents(
        "contents",
        "Contents",
        SatellitePlacement.Docked(DockSide.Right, order = 1, extent = 170.dp),
        openAtStart = true,
        fixed = true,
    ),
    Notes("notes", "Notes", SatellitePlacement.Docked(DockSide.Right, order = 2, extent = 220.dp), openAtStart = false),
    Glossary("glossary", "Glossary", SatellitePlacement.Docked(DockSide.Left, extent = 240.dp), openAtStart = false),
    Bookmarks(
        "bookmarks",
        "Bookmarks",
        SatellitePlacement.Docked(DockSide.Bottom, extent = 220.dp),
        openAtStart = true,
    ),
    Search("search", "Search", SatellitePlacement.Docked(DockSide.Bottom, extent = 200.dp), openAtStart = false),
    ;

    val dockSides: Set<DockSide> get() = if (fixed) setOf(DockSide.Right) else ReaderDockSides

    /** Panes are per window: a window's dock is its own furniture. */
    fun idIn(groupId: String): String = "$groupId-$id"
}

@Immutable
data class ReaderLive(
    val style: ReaderStyle = ReaderStyle.Classic,
    val rightToLeft: Boolean = false,
    /** `layeredSides = {Right}`: library | contents | notes as columns, not a stack. */
    val layeredRight: Boolean = true,
    /** `sideOrder` starting with Right: the navigation runs the full height, the bottom panes stop at it. */
    val navigationOutermost: Boolean = true,
) {
    val sideOrder: List<DockSide>
        get() =
            if (navigationOutermost) {
                listOf(DockSide.Right, DockSide.Bottom, DockSide.Left, DockSide.Top)
            } else {
                listOf(DockSide.Top, DockSide.Bottom, DockSide.Left, DockSide.Right)
            }
}

/** What the reader remembers about a book wherever its tab is shown. */
class BookState {
    var chapter by mutableIntStateOf(0)
    private val selections = mutableStateMapOf<Pane, Int>()

    fun selected(pane: Pane): Int = selections[pane] ?: -1

    fun select(
        pane: Pane,
        index: Int,
    ) {
        selections[pane] = index
    }
}

/** Books as tabs, one dock of panes per reader window. */
class ReaderModel(
    live: ReaderLive,
) : ComposedModel<ReaderLive> {
    override val tabs = TabWorkspace(defaultWindowSize = DpSize(1280.dp, 820.dp), captureThumbnails = true)
    override val docks = PerWindowSatellites()
    override var live: ReaderLive by mutableStateOf(live)

    val books =
        DocumentSet(
            seed =
                listOf(
                    book("pride-and-prejudice", "Pride and Prejudice", 61),
                    book("moby-dick", "Moby-Dick", 135),
                    book("walden", "Walden", 18),
                ),
            make = { n -> book("book-$n", ExtraTitles[(n - 1) % ExtraTitles.size], 24) },
        )

    private val states = mutableStateMapOf<String, BookState>()

    fun stateOf(bookId: String): BookState = states.getOrPut(bookId) { BookState() }

    fun forget(bookId: String) {
        books.forget(bookId)
        states.remove(bookId)
    }

    override fun openDocument() {
        books.open()
    }

    fun isOpen(
        groupId: String,
        pane: Pane,
    ): Boolean = docks.of(groupId).satellite(pane.idIn(groupId))?.isOpen == true

    /** Bookmarks and search share the bottom: opening one closes the other. */
    fun toggle(
        groupId: String,
        pane: Pane,
    ) {
        val workspace = docks.of(groupId)
        if (!isOpen(groupId, pane)) {
            when (pane) {
                Pane.Bookmarks -> workspace.close(Pane.Search.idIn(groupId))
                Pane.Search -> workspace.close(Pane.Bookmarks.idIn(groupId))
                else -> Unit
            }
        }
        workspace.toggle(pane.idIn(groupId))
    }

    override fun resetDock(groupId: String) {
        val workspace = docks.of(groupId)
        for (pane in Pane.entries) {
            val id = pane.idIn(groupId)
            workspace.dock(id, pane.home.side, order = pane.home.order)
            pane.home.extent?.let { workspace.setDockedExtent(id, it) }
            workspace.setDockedWeight(id, pane.home.weight)
            if (pane.openAtStart) workspace.open(id) else workspace.close(id)
        }
    }

    private companion object {
        val ExtraTitles = listOf("Middlemarch", "Persuasion", "Dracula", "Emma", "Frankenstein")

        fun book(
            id: String,
            title: String,
            chapters: Int,
        ) = Document(id, title, "$chapters chapters", "", List(chapters) { "Chapter ${it + 1}" })
    }
}
