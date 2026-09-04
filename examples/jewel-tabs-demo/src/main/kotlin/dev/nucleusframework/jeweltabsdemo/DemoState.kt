package dev.nucleusframework.jeweltabsdemo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.TabLayoutSnapshot
import dev.nucleusframework.window.tao.TabWorkspace

/**
 * One open file of the demo — one tab of the workspace.
 *
 * @property id the tab's identity, stable while the file is open.
 * @property title shown on the tab and, while it is selected, as the window title.
 * @property draft what its editor starts with.
 */
class Document(
    val id: String,
    val title: String,
    val draft: String,
)

/**
 * Everything the demo drives: the [workspace] and the files declared against it.
 *
 * The file list is the app's own — the workspace owns *where* each tab is, never
 * whether it exists — so opening a file means adding to this list, and a tab the
 * user closes has to be dropped from it ([forget]) or it would be declared again.
 */
class DemoState {
    val workspace = TabWorkspace(defaultWindowSize = DpSize(WINDOW_WIDTH_DP.dp, WINDOW_HEIGHT_DP.dp))

    /** The open files, in declaration order. One tab each. */
    val documents =
        mutableStateListOf(
            Document("main", "Main.kt", "fun main() = nucleusApplication { }"),
            Document("strip", "JewelTabStrip.kt", "TabStrip(tabs, style = JewelTheme.editorTabStyle)"),
            Document("build", "build.gradle.kts", "implementation(libs.jewel.int.ui.standalone)"),
        )

    /** The layout captured by "Save layout", ready for "Restore layout". */
    var savedLayout: TabLayoutSnapshot? by mutableStateOf(null)
        private set

    private var opened = 0

    /** Opens a new file; the `Tab` declaration puts it in the window focused last. */
    fun open() {
        opened++
        documents += Document("scratch-$opened", "scratch$opened.kt", "")
    }

    /** Drops the file [id] once its tab is gone from the workspace. */
    fun forget(id: String) {
        documents.removeAll { it.id == id }
    }

    fun saveLayout() {
        savedLayout = workspace.snapshot()
    }

    fun restoreLayout() {
        savedLayout?.let(workspace::restore)
    }

    private companion object {
        const val WINDOW_WIDTH_DP = 900
        const val WINDOW_HEIGHT_DP = 600
    }
}
