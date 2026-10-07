package dev.nucleusframework.lab.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

@Composable
fun DecoratedWindowScope.LabShell(
    shell: ShellViewModel,
    state: ShellState,
) {
    val copy = rememberCopyToClipboard()
    LaunchedEffect(shell) {
        shell.effects.collect { effect ->
            when (effect) {
                is ShellEffect.CopyToClipboard -> copy(effect.text)
            }
        }
    }

    val probes = shell.probes
    val current = probes.firstOrNull { it.descriptor.id == state.selected } ?: probes.first()

    LabWindowAppearance()
    Column(Modifier.fillMaxSize().background(LabTheme.colors.panel)) {
        LabTitleBar { _ -> TitleBarContent(shell, state) }
        Divider()
        Box(Modifier.weight(1f)) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(probes, state, onSelect = {
                    shell.onIntent(ShellIntent.Select(it))
                }, modifier = Modifier.width(264.dp).fillMaxHeight())
                Divider(Orientation.Vertical)
                Column(Modifier.weight(1f).fillMaxHeight().background(LabTheme.colors.background)) {
                    ProbeHeader(current.descriptor, shell)
                    Divider()
                    Row(Modifier.weight(1f)) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            ProbeHost(
                                current,
                                generation = state.generations[current.descriptor.id] ?: 0,
                                shell = shell,
                            )
                        }
                        if (state.checksOpen && current.descriptor.checks.isNotEmpty()) {
                            Divider(Orientation.Vertical)
                            ChecksPanel(current.descriptor, state, shell, Modifier.width(320.dp).fillMaxHeight())
                        }
                    }
                    if (state.timelineOpen) {
                        Divider()
                        TimelinePanel(shell, state, current.descriptor.id, Modifier.fillMaxWidth().height(220.dp))
                    }
                }
            }
            if (state.paletteOpen) {
                CommandPalette(probes, shell, Modifier.fillMaxSize())
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
        TitleBarButton(LabIcons.Checklist, "Toggle checks", active = state.checksOpen) {
            shell.onIntent(ShellIntent.ToggleChecks)
        }
        TitleBarButton(LabIcons.Terminal, "Toggle timeline", active = state.timelineOpen) {
            shell.onIntent(ShellIntent.ToggleTimeline)
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

/** The header strip of a shell panel (checks, timeline): IntelliJ's tool window header. */
@Composable
internal fun PanelTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .background(LabTheme.colors.panel)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = LabTheme.typography.heading, modifier = Modifier.weight(1f))
        trailing()
    }
}
