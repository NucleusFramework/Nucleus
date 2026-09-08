package dev.nucleusframework.readerdockdemo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatelliteLayoutSnapshot
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.TabWorkspace

/** The two looks of the reader: dividers everywhere, or every pane a rounded card. */
enum class ReaderStyle {
    Classic,
    Islands,
}

/**
 * Where a pane may be docked: anywhere but the top. The reader's top is its
 * tab strip and the text's own header; a pane dragged there is refused, and
 * the top strip never lights up.
 */
val ReaderDockSides: Set<DockSide> = setOf(DockSide.Left, DockSide.Right, DockSide.Bottom)

/** The sides [Pane.fixed] panes accept: the right of the text, where the reader puts them. */
val ReaderFixedDockSides: Set<DockSide> = setOf(DockSide.Right)

/**
 * One pane of the reader: a satellite with a home in the dock.
 *
 * [fixed] is the reader's furniture — the book tree and the table of contents
 * belong on the right of the text, in that order, and nowhere else: they
 * cannot be torn into a window of their own, moved to another side, or
 * reordered, and no other pane can be dropped in front of them. They can
 * still be hidden and resized.
 */
enum class Pane(
    val id: String,
    val title: String,
    val home: SatellitePlacement.Docked,
    val openAtStart: Boolean,
    val fixed: Boolean = false,
) {
    Tree(
        "tree",
        "ספרים",
        SatellitePlacement.Docked(DockSide.Right, order = 0, extent = 200.dp),
        openAtStart = true,
        fixed = true,
    ),
    Toc(
        "toc",
        "תוכן",
        SatellitePlacement.Docked(DockSide.Right, order = 1, extent = 170.dp),
        openAtStart = true,
        fixed = true,
    ),
    Notes("notes", "הערות", SatellitePlacement.Docked(DockSide.Right, order = 2, extent = 220.dp), openAtStart = false),
    Targum("targum", "תרגום", SatellitePlacement.Docked(DockSide.Left, extent = 240.dp), openAtStart = false),
    Comments("comments", "מפרשים", SatellitePlacement.Docked(DockSide.Bottom, extent = 220.dp), openAtStart = true),
    Sources("sources", "מקורות", SatellitePlacement.Docked(DockSide.Bottom, extent = 200.dp), openAtStart = false),
    ;

    /**
     * This pane's entry id in the workspace of the reader window [groupId].
     *
     * The panes are per **window**, not per book: a window's dock is its own
     * furniture, and a tab change must neither create nor destroy a panel.
     * What follows the tab is what the panes *draw*.
     */
    fun idIn(groupId: String): String = "$groupId-$id"
}

/** One sefer: one tab, its chapters, and the text they hold. */
class Book(
    val id: String,
    val title: String,
    val chapters: List<String>,
)

/**
 * What the reader remembers about a book, wherever its tab is shown: which
 * chapter is open and which line each pane has selected.
 *
 * It lives here rather than in the panes because it belongs to the book: a tab
 * moved to another window, or brought back after being closed and reopened,
 * has to find it unchanged — and the panes that draw it belong to a window,
 * not to a book.
 */
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

/**
 * Everything the demo drives: the seforim as tabs, and one dock of panes per
 * reader window.
 *
 * [tabs] owns which windows exist and which sefer each window shows.
 * [panesOfWindow] hands out one [SatelliteWorkspace] **per window**, which is
 * what lets a tab change leave the dock alone: the panes exist as long as
 * their window does, and only their content follows the selected tab. Tear a
 * tab into a window of its own and it arrives with a dock of its own, so two
 * windows read two seforim side by side, each with its own pane widths.
 */
class ReaderState {
    // `captureThumbnails` is what puts the page itself on a sefer's hover
    // card: the workspace keeps a reduced picture of the body each tab last
    // showed. Off by default — it costs a layer and a readback per tab.
    val tabs =
        TabWorkspace(
            defaultWindowSize = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp),
            captureThumbnails = true,
        )

    /** The open seforim, in declaration order. One tab each. */
    val books =
        mutableStateListOf(
            Book("bereshit", "בראשית", chapterNames(BERESHIT_CHAPTERS)),
            Book("shemot", "שמות", chapterNames(SHEMOT_CHAPTERS)),
            Book("tehillim", "תהילים", chapterNames(TEHILLIM_CHAPTERS)),
        )

    var style: ReaderStyle by mutableStateOf(ReaderStyle.Classic)

    private val workspaces = mutableStateMapOf<String, SatelliteWorkspace>()
    private val bookStates = mutableStateMapOf<String, BookState>()
    private val savedLayouts = mutableStateMapOf<String, SatelliteLayoutSnapshot>()

    /** The pane workspace of the reader window [groupId], created on first use. */
    fun panesOfWindow(groupId: String): SatelliteWorkspace = workspaces.getOrPut(groupId) { SatelliteWorkspace() }

    /** Drops the workspace of a window that is gone. */
    fun forgetWindow(groupId: String) {
        workspaces.remove(groupId)
        savedLayouts.remove(groupId)
    }

    /** The book [id] names, or `null` once its tab has been closed. */
    fun book(id: String): Book? = books.firstOrNull { it.id == id }

    /** What the reader remembers about [bookId], created on first use. */
    fun stateOf(bookId: String): BookState = bookStates.getOrPut(bookId) { BookState() }

    /** Drops a book — and what the reader remembered about it — once its tab is gone. */
    fun forget(bookId: String) {
        books.removeAll { it.id == bookId }
        bookStates.remove(bookId)
    }

    private var opened = 0

    /** Opens another sefer; its tab lands in the window focused last. */
    fun openBook() {
        opened++
        val title = ExtraTitles[(opened - 1) % ExtraTitles.size]
        books += Book("sefer-$opened", title, chapterNames(EXTRA_CHAPTERS))
    }

    /** Brings the book [id] to the front of whichever window shows its tab. */
    fun show(id: String) {
        tabs.select(id)
    }

    // ── Per-window pane layout ───────────────────────────────────────────

    fun isOpen(
        groupId: String,
        pane: Pane,
    ): Boolean = panesOfWindow(groupId).satellite(pane.idIn(groupId))?.isOpen == true

    /** Shows or hides a pane of one window. Commentaries and sources share the bottom, so one closes the other. */
    fun toggle(
        groupId: String,
        pane: Pane,
    ) {
        val workspace = panesOfWindow(groupId)
        val opening = !isOpen(groupId, pane)
        when (pane) {
            Pane.Comments -> if (opening) workspace.close(Pane.Sources.idIn(groupId))
            Pane.Sources -> if (opening) workspace.close(Pane.Comments.idIn(groupId))
            else -> Unit
        }
        workspace.toggle(pane.idIn(groupId))
    }

    fun savedLayout(groupId: String): SatelliteLayoutSnapshot? = savedLayouts[groupId]

    fun saveLayout(groupId: String) {
        savedLayouts[groupId] = panesOfWindow(groupId).snapshot()
    }

    fun restoreLayout(groupId: String) {
        savedLayouts[groupId]?.let(panesOfWindow(groupId)::restore)
    }

    /** Every pane of one window back where it started, at its starting width. */
    fun resetLayout(groupId: String) {
        val workspace = panesOfWindow(groupId)
        for (pane in Pane.entries) {
            val id = pane.idIn(groupId)
            workspace.dock(id, pane.home.side, order = pane.home.order)
            pane.home.extent?.let { workspace.setDockedExtent(id, it) }
            workspace.setDockedWeight(id, pane.home.weight)
            if (pane.openAtStart) workspace.open(id) else workspace.close(id)
        }
    }

    private companion object {
        const val WINDOW_W_DP = 1280
        const val WINDOW_H_DP = 820
        const val BERESHIT_CHAPTERS = 50
        const val SHEMOT_CHAPTERS = 40
        const val TEHILLIM_CHAPTERS = 30
        const val EXTRA_CHAPTERS = 24

        val ExtraTitles = listOf("ויקרא", "במדבר", "דברים", "משלי", "איוב")

        fun chapterNames(count: Int): List<String> = List(count) { "פרק ${it + 1}" }
    }
}
