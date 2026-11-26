package dev.nucleusframework.lab.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.designsystem.Divider
import dev.nucleusframework.lab.designsystem.Icon
import dev.nucleusframework.lab.designsystem.LabIcons
import dev.nucleusframework.lab.designsystem.LabShapes
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.LabTitleBar
import dev.nucleusframework.lab.designsystem.LabWindowAppearance
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.designsystem.TitleBarButton
import dev.nucleusframework.lab.designsystem.actionsAlignment
import dev.nucleusframework.lab.designsystem.rememberCopyToClipboard
import dev.nucleusframework.window.DecoratedWindowScope
import dev.nucleusframework.window.TitleBarScope
import dev.nucleusframework.window.tao.DockLayout

/** Cmd/Ctrl+K opens the palette, Esc closes it. */
fun shellShortcut(
    event: KeyEvent,
    shell: ShellViewModel,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val command = if (Platform.Current == Platform.MacOS) event.isMetaPressed else event.isCtrlPressed
    return when {
        command && event.key == Key.K -> {
            shell.onIntent(ShellIntent.SetPalette(!shell.state.value.paletteOpen))
            true
        }
        event.key == Key.Escape && shell.state.value.paletteOpen -> {
            shell.onIntent(ShellIntent.SetPalette(false))
            true
        }
        else -> false
    }
}

/**
 * The main window: title bar over a `DockLayout` whose content is the selected probe and
 * whose panels are the shell's panes ([ShellPanes]) while they are docked.
 */
@Composable
fun DecoratedWindowScope.LabShell(
    shell: ShellViewModel,
    state: ShellState,
    panes: ShellWorkspace,
) {
    val copy = rememberCopyToClipboard()
    LaunchedEffect(shell) {
        shell.effects.collect { effect ->
            when (effect) {
                is ShellEffect.CopyToClipboard -> copy(effect.text)
            }
        }
    }

    val current = shell.probe(state.selected)

    LabWindowAppearance()
    Column(Modifier.fillMaxSize().background(LabTheme.colors.panel)) {
        LabTitleBar { _ -> TitleBarContent(shell, state) }
        Divider()
        Box(Modifier.weight(1f)) {
            DockLayout(
                workspace = panes.workspace,
                modifier = Modifier.fillMaxSize(),
                sideOrder = ShellSideOrder,
                splitter = { ShellSplitter() },
            ) {
                Column(Modifier.fillMaxSize().background(LabTheme.colors.background)) {
                    ProbeHeader(current.descriptor, shell)
                    Divider()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        ProbeHost(
                            current,
                            generation = state.generations[current.descriptor.id] ?: 0,
                            shell = shell,
                        )
                    }
                }
            }
            if (state.paletteOpen) {
                CommandPalette(shell.probes, shell, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun TitleBarScope.TitleBarContent(
    shell: ShellViewModel,
    state: ShellState,
) {
    val colors = LabTheme.colors
    val searchInteraction = remember { MutableInteractionSource() }
    val searchHovered by searchInteraction.collectIsHoveredAsState()
    Row(
        Modifier
            .align(Alignment.CenterHorizontally)
            .width(380.dp)
            .heightIn(min = 26.dp)
            .clip(LabShapes.small)
            .hoverable(searchInteraction)
            .background(if (searchHovered) colors.raised else colors.background)
            .border(1.dp, if (searchHovered) colors.borderStrong else colors.border, LabShapes.small)
            .titleBarClickable { shell.onIntent(ShellIntent.SetPalette(true)) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(LabIcons.Search, null)
        Text(
            state.environment?.summary ?: "Search probes",
            style = LabTheme.typography.small,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (Platform.Current == Platform.MacOS) "⌘K" else "Ctrl+K",
            style = LabTheme.typography.small,
            color = colors.textMuted,
        )
    }
    Row(
        Modifier.align(actionsAlignment).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (pane in ShellPane.entries) {
            TitleBarButton(pane.icon, "Toggle ${pane.name.lowercase()}", active = pane in state.openPanes) {
                shell.onIntent(ShellIntent.TogglePane(pane))
            }
        }
        TitleBarButton(LabIcons.Copy, "Copy full report") { shell.onIntent(ShellIntent.CopyReport(null)) }
        TitleBarButton(
            when (state.theme) {
                ThemeMode.System -> LabIcons.ThemeSystem
                ThemeMode.Light -> LabIcons.ThemeLight
                ThemeMode.Dark -> LabIcons.ThemeDark
            },
            "Theme: ${state.theme}",
        ) { shell.onIntent(ShellIntent.CycleTheme) }
    }
}
