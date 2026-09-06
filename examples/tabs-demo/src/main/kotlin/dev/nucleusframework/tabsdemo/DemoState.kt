package dev.nucleusframework.tabsdemo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.TabLayoutSnapshot
import dev.nucleusframework.window.tao.TabWorkspace

/**
 * One document of the demo — one tab of the workspace.
 *
 * @property id the tab's identity, stable for as long as the document is open.
 * @property title shown on the tab and, while it is the selected one, as the
 *   title of the window holding it.
 * @property draft what its editor starts with.
 */
class Document(
    val id: String,
    val title: String,
    val draft: String,
)

/**
 * Everything the demo drives, hoisted to the application: the [workspace] and
 * the documents declared against it.
 *
 * The document list is the app's own — the workspace owns *where* each tab is,
 * never whether it exists. So opening a document means adding to this list, and
 * a tab the user closes has to be dropped from it ([forget]) or it would be
 * declared all over again.
 */
class DemoState {
    val workspace = TabWorkspace(defaultWindowSize = DpSize(WINDOW_WIDTH_DP.dp, WINDOW_HEIGHT_DP.dp))

    /** The open documents, in declaration order. One tab each. */
    val documents =
        mutableStateListOf(
            Document("readme", "README.md", "# Tabs demo\n\nDrag a tab out of this window."),
            Document("main", "Main.kt", "fun main() = nucleusApplication { }"),
            Document("build", "build.gradle.kts", "plugins { id(\"dev.nucleusframework\") }"),
        )

    /** The layout captured by "Save layout", ready for "Restore layout". */
    var savedLayout: TabLayoutSnapshot? by mutableStateOf(null)
        private set

    private var opened = 0

    /**
     * Opens a new document. It is only added to the list here; the `Tab`
     * declaration that follows puts it in the window focused last, exactly
     * where a browser opens a new tab.
     */
    fun open() {
        opened++
        documents += Document("note-$opened", "Untitled $opened", "")
    }

    /** Drops the document [id] once its tab is gone from the workspace. */
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
        const val WINDOW_HEIGHT_DP = 620
    }
}
