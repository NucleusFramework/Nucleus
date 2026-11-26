package dev.nucleusframework.lab.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.LabLazyColumn
import dev.nucleusframework.lab.designsystem.LabShapes
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.SelectableRow
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.designsystem.TextField

/** Fuzzy-ish match: every query word must appear in title, id, domain, modules or keywords. */
fun Probe.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val d = descriptor
    val haystack =
        (listOf(d.title, d.id.value, d.domain.title, d.summary) + d.modules + d.keywords)
            .joinToString(" ")
            .lowercase()
    return query
        .lowercase()
        .split(' ')
        .filter { it.isNotBlank() }
        .all { it in haystack }
}

/** Search-everywhere over the probes: type, arrow keys, Enter. */
@Composable
fun CommandPalette(
    probes: List<Probe>,
    shell: ShellViewModel,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var highlighted by remember { mutableIntStateOf(0) }
    val results = probes.filter { it.matches(query) }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(highlighted) { if (results.isNotEmpty()) listState.animateScrollToItem(highlighted) }
    val colors = LabTheme.colors

    Box(
        modifier
            .background(Color.Black.copy(alpha = if (colors.isDark) 0.45f else 0.2f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                shell.onIntent(ShellIntent.SetPalette(false))
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .padding(top = 64.dp)
                .width(640.dp)
                .shadow(16.dp, LabShapes.specimen)
                .clip(LabShapes.specimen)
                .background(colors.panel)
                .border(1.dp, colors.borderStrong, LabShapes.specimen)
                .clickable(enabled = false) {}
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    highlighted = 0
                },
                placeholder = "Probe, module, platform feature…",
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    highlighted = (highlighted + 1).coerceAtMost(results.lastIndex.coerceAtLeast(0))
                                    true
                                }
                                Key.DirectionUp -> {
                                    highlighted = (highlighted - 1).coerceAtLeast(0)
                                    true
                                }
                                Key.Enter -> {
                                    results
                                        .getOrNull(
                                            highlighted,
                                        )?.let { shell.onIntent(ShellIntent.Select(it.descriptor.id)) }
                                    true
                                }
                                Key.Escape -> {
                                    shell.onIntent(ShellIntent.SetPalette(false))
                                    true
                                }
                                else -> false
                            }
                        },
            )
            if (results.isEmpty()) {
                EmptyState("No probe matches.", Modifier.padding(12.dp))
            } else {
                LabLazyColumn(Modifier.heightIn(max = 420.dp), state = listState) {
                    itemsIndexed(results, key = { _, p -> p.descriptor.id.value }) { index, probe ->
                        PaletteRow(
                            probe,
                            index == highlighted,
                        ) { shell.onIntent(ShellIntent.Select(probe.descriptor.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(
    probe: Probe,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val d = probe.descriptor
    val colors = LabTheme.colors
    SelectableRow(selected = highlighted, onClick = onClick) {
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Text(d.title)
            Text(
                d.summary,
                style = LabTheme.typography.small,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(d.domain.title, style = LabTheme.typography.small, color = colors.textMuted)
        if (!d.supportsCurrentPlatform) Text("n/a", style = LabTheme.typography.small, color = colors.textMuted)
    }
}
