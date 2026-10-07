package dev.nucleusframework.lab.probes.workspace.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.lab.probes.workspace.common.Document
import dev.nucleusframework.lab.probes.workspace.common.DocumentSet
import dev.nucleusframework.lab.probes.workspace.common.SessionModel
import dev.nucleusframework.lab.probes.workspace.common.SessionModelViewModel
import dev.nucleusframework.lab.probes.workspace.common.SessionState
import dev.nucleusframework.lab.probes.workspace.common.TabsObservation
import dev.nucleusframework.lab.probes.workspace.common.diffTabs
import dev.nucleusframework.lab.probes.workspace.common.mergeAll
import dev.nucleusframework.lab.probes.workspace.common.observe
import dev.nucleusframework.lab.probes.workspace.common.spreadInto
import dev.nucleusframework.lab.probes.workspace.common.tearOffFromItsWindow
import dev.nucleusframework.lab.probes.workspace.common.toJson
import dev.nucleusframework.window.tao.TabLayoutSnapshot
import dev.nucleusframework.window.tao.TabWorkspace
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

/** What one open tab session holds: the workspace and the documents declared against it. */
class TabsSessionModel(
    options: TabsOptions,
    live: TabsLive,
) : SessionModel<TabsLive> {
    override var live: TabsLive by mutableStateOf(live)

    val workspace =
        TabWorkspace(defaultWindowSize = DpSize(900.dp, 620.dp), captureThumbnails = options.captureThumbnails)

    val documents =
        DocumentSet(
            seed = List(options.initialTabs) { seedDocument(it) },
            make = { n -> Document("note-$n", "Untitled $n", "untitled-$n.txt", "", Unit) },
        )

    private companion object {
        val Seeds =
            listOf(
                Triple("README.md", "docs/README.md", "# Tabs\n\nDrag a tab out of this window."),
                Triple("Main.kt", "src/main/kotlin/Main.kt", "fun main() = nucleusApplication { }"),
                Triple("build.gradle.kts", "build.gradle.kts", "plugins { id(\"dev.nucleusframework\") }"),
                Triple("Workspace.kt", "src/main/kotlin/Workspace.kt", "val workspace = TabWorkspace()"),
                Triple("notes.md", "notes.md", "- tear off\n- merge\n- reorder"),
            )

        fun seedDocument(index: Int): Document<Unit> {
            val (title, path, draft) = Seeds[index % Seeds.size]
            val name = if (index < Seeds.size) title else title.replaceFirst(".", "-$index.")
            return Document("doc-$index", name, path, draft, Unit)
        }
    }
}

/** Which chrome the strip wears: the stock one, or IntelliJ's own `TabStrip` through Jewel. */
enum class TabsFlavor { Stock, Jewel }

/**
 * Shared by the stock and the Jewel tab probes: same workspace, same controls, same
 * observations — only the session's chrome differs, which is the point of the comparison.
 */
abstract class TabWorkspaceViewModel(
    private val flavor: TabsFlavor,
    host: SessionHost,
    timeline: Timeline,
    source: ProbeId,
) : SessionModelViewModel<TabsLive, TabsData, TabsIntent, TabsEvent, TabsSessionModel>(
        SessionState(live = TabsLive(), data = TabsData()),
        TabsReducer,
        host,
        timeline,
        source,
    ) {
    private var savedLayout: TabLayoutSnapshot? = null

    override val sessionTitle =
        when (flavor) {
            TabsFlavor.Stock -> "Tab workspace"
            TabsFlavor.Jewel -> "Jewel tab workspace"
        }

    override fun createModel(state: TabsState) = TabsSessionModel(state.data.options, state.live)

    override fun content(model: TabsSessionModel): @Composable NucleusApplicationScope.(() -> Unit) -> Unit =
        { close ->
            when (flavor) {
                TabsFlavor.Stock -> StockTabsSession(model, close)
                TabsFlavor.Jewel -> JewelTabsSession(model, close)
            }
        }

    override fun onOpened(model: TabsSessionModel) {
        var previous = TabsObservation()
        mirror({ model.workspace.observe() }) { observation ->
            diffTabs(previous, observation).forEach { dispatchProbe(TabsEvent.Changed(it)) }
            previous = observation
            reduceProbeSilently(TabsEvent.Observed(observation))
        }
    }

    override suspend fun handleProbe(intent: TabsIntent) {
        when (intent) {
            is TabsIntent.SetOptions -> dispatchProbe(TabsEvent.OptionsChanged(intent.options))
            is TabsIntent.AddTabs -> withModel { repeat(intent.count) { documents.open() } }
            is TabsIntent.SpreadInto ->
                withModel {
                    val reached = workspace.spreadInto(intent.windows)
                    if (reached < intent.windows) {
                        refuse("only $reached window(s): not enough tabs, or a window has no frame yet")
                    }
                }
            TabsIntent.MergeAll -> withModel { workspace.mergeAll() }
            TabsIntent.TearOffSelected -> withModel { tearOffSelected() }
            TabsIntent.SaveLayout ->
                withModel {
                    val snapshot = workspace.snapshot()
                    savedLayout = snapshot
                    dispatchProbe(TabsEvent.LayoutSaved(snapshot.toJson()))
                }
            TabsIntent.RestoreLayout ->
                withModel {
                    val snapshot = savedLayout
                    if (snapshot == null) {
                        refuse("nothing saved yet")
                    } else {
                        workspace.restore(snapshot)
                        dispatchProbe(TabsEvent.LayoutRestored(snapshot.groups.size))
                    }
                }
        }
    }

    private fun TabsSessionModel.tearOffSelected() {
        val group = workspace.activeGroup ?: workspace.groups.firstOrNull()
        val tab = group?.selectedId
        when {
            tab == null -> refuse("no selected tab")
            group.ids.size < 2 -> refuse("$tab is alone in its window: a tear-off would only move it")
            !workspace.tearOffFromItsWindow(tab) -> refuse("the window reports no frame (outerBoundsPx)")
        }
    }
}

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class TabsViewModel(
    host: SessionHost,
    timeline: Timeline,
) : TabWorkspaceViewModel(TabsFlavor.Stock, host, timeline, TabsProbe.ID)

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class JewelTabsViewModel(
    host: SessionHost,
    timeline: Timeline,
) : TabWorkspaceViewModel(TabsFlavor.Jewel, host, timeline, JewelTabsProbe.ID)
