package dev.nucleusframework.lab.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.lab.app.LabGraph
import dev.nucleusframework.lab.core.timeline.EntryKind
import dev.nucleusframework.lab.core.timeline.UiThread
import dev.nucleusframework.lab.designsystem.LabDecoratedWindow
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.window.tao.JoinSatelliteWorkspace
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.metroViewModel

/** `-Dlab.theme=system|light|dark` picks the starting theme (screenshots, bug reproductions). */
private val StartupTheme: ThemeMode? =
    System.getProperty("lab.theme")?.let { name ->
        ThemeMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

/** Root of the Lab: DI locals, theme, the shell window, its panes and every open session window. */
@Composable
fun NucleusApplicationScope.LabApp(graph: LabGraph) {
    remember {
        UiThread.capture()
        graph.timeline.record(null, EntryKind.Log, "Lab started on ${Thread.currentThread().name}")
        graph.commands.handleStartupProperty()
    }
    val rootOwner =
        remember {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }

    CompositionLocalProvider(
        LocalMetroViewModelFactory provides graph.metroViewModelFactory,
        LocalViewModelStoreOwner provides rootOwner,
    ) {
        val shell = metroViewModel<ShellViewModel>()
        val state by shell.state.collectAsState()
        LaunchedEffect(shell) {
            val wanted = StartupTheme ?: return@LaunchedEffect
            // CycleTheme is reduced asynchronously: count the steps up front.
            val entries = ThemeMode.entries
            val steps = (wanted.ordinal - shell.state.value.theme.ordinal + entries.size) % entries.size
            repeat(steps) { shell.onIntent(ShellIntent.CycleTheme) }
        }
        val isDark =
            when (state.theme) {
                ThemeMode.System -> isSystemInDarkMode()
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
            }

        LabTheme(isDark = isDark) {
            val windowState =
                rememberWindowState(size = DpSize(1440.dp, 900.dp), position = WindowPosition.Aligned(Alignment.Center))
            val panes = rememberShellWorkspace(shell, state)
            LabDecoratedWindow(
                title = "Nucleus Lab",
                onCloseRequest = {
                    // The debounced save may not have run yet: the layout is written before the exit.
                    shell.saveLayout(panes.snapshot(), now = true)
                    exitApplication()
                },
                state = windowState,
                minimumSize = DpSize(960.dp, 600.dp),
                onPreviewKeyEvent = { shellShortcut(it, shell) },
            ) {
                // The only member: the owner of the floating panes and the host of the docked ones.
                JoinSatelliteWorkspace(panes.workspace)
                LaunchedEffect(Unit) { shell.refreshEnvironment() }
                LabShell(shell, state, panes)
            }
            ShellPanes(panes, shell, state)

            val sessions by graph.sessions.sessions.collectAsState()
            sessions.forEach { session ->
                key(session.key) {
                    session.content(this@LabApp) { graph.sessions.close(session.key) }
                }
            }
        }
    }
}
