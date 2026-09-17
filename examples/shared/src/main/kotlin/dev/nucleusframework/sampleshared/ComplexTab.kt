package dev.nucleusframework.sampleshared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Stress-test surface for the per-node a11y diff path.
 *
 * Exercises (in this order, all driveable from AT-SPI):
 *   - Dynamic todo list — add / remove / reorder / toggle / rename items.
 *     Tests topology mutations + per-item content changes through partial
 *     wire-format pushes. Each item carries a stable testTag so the
 *     verification scripts can find it across reorderings.
 *   - Live filter — narrows the visible item set without mutating the model.
 *     Tests subtree visibility flips (nodes appearing / disappearing).
 *   - Auto-ticking progress — a counter increments on a 100 ms loop, driving
 *     a slider's `progressBarRangeInfo`. Tests high-frequency partials.
 *   - Expandable groups — three nested expand / collapse panels with their
 *     own a11y `Expand` / `Collapse` actions. Tests AccessKit's expanded /
 *     collapsed state propagation through partials.
 *   - Conditional form — radio selection toggles which downstream
 *     checkboxes are present in the tree. Tests subtree-replacement diffs.
 */
@Composable
fun ComplexTab(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color(0xFF12141A))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BasicText(
            text = "Complex a11y stress",
            modifier = Modifier.semantics { heading() },
            style = TextStyle(color = Color(0xFFE6E6E6), fontSize = 18.sp, fontWeight = FontWeight.Bold),
        )

        TodoListSection()
        AutoTickerSection()
        ExpandablesSection()
        ConditionalFormSection()

        Spacer(Modifier.height(24.dp))
    }
}

// ── Todo list ────────────────────────────────────────────────────────────

private data class TodoItem(
    val id: Long,
    val title: String,
    val done: Boolean,
)

@Composable
private fun TodoListSection() {
    val nextId = remember { mutableStateOf(1L) }
    val items =
        remember {
            mutableStateListOf(
                TodoItem(1L, "Buy milk", false),
                TodoItem(2L, "Write tests", true),
                TodoItem(3L, "Ship it", false),
            ).also { nextId.value = 4L }
        }
    var filter by remember { mutableStateOf(TextFieldValue("")) }

    val filtered by remember(items, filter) {
        derivedStateOf {
            val q = filter.text.trim().lowercase()
            if (q.isEmpty()) {
                items.toList()
            } else {
                items.filter { it.title.lowercase().contains(q) }
            }
        }
    }
    val doneCount by remember(items) { derivedStateOf { items.count { it.done } } }

    Section("Todo list") {
        // Status line — live region so AT clients hear the count change.
        BasicText(
            text = "${items.size} item(s) · $doneCount done · showing ${filtered.size}",
            modifier =
                Modifier
                    .testTag("todo-status")
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "${items.size} items, $doneCount done, showing ${filtered.size}"
                    },
            style = labelStyle,
        )

        // Add / clear controls.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComplexButton(
                label = "Add item",
                tag = "todo-add",
                onClick = {
                    val id = nextId.value
                    nextId.value = id + 1
                    items.add(TodoItem(id, "Item #$id", false))
                },
            )
            ComplexButton(
                label = "Clear done",
                tag = "todo-clear-done",
                onClick = { items.removeAll { it.done } },
            )
            ComplexButton(
                label = "Reset",
                tag = "todo-reset",
                onClick = {
                    items.clear()
                    nextId.value = 1L
                    items.addAll(
                        listOf(
                            TodoItem(nextId.value++, "Buy milk", false),
                            TodoItem(nextId.value++, "Write tests", true),
                            TodoItem(nextId.value++, "Ship it", false),
                        ),
                    )
                },
            )
        }

        // Filter input.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(text = "Filter:", style = labelStyle)
            Box(
                modifier =
                    Modifier
                        .width(220.dp)
                        .background(Color(0xFF1F2937), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF374151), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                BasicTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    textStyle = labelStyle,
                    cursorBrush = SolidColor(Color(0xFF8AB4FF)),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("todo-filter"),
                )
            }
        }

        // Items.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((index, item) in filtered.withIndex()) {
                key(item.id) {
                    TodoRow(
                        item = item,
                        canMoveUp = index > 0,
                        canMoveDown = index < filtered.size - 1,
                        onToggle = {
                            val pos = items.indexOfFirst { it.id == item.id }
                            if (pos >= 0) items[pos] = items[pos].copy(done = !items[pos].done)
                        },
                        onDelete = { items.removeAll { it.id == item.id } },
                        onMoveUp = {
                            val pos = items.indexOfFirst { it.id == item.id }
                            if (pos > 0) {
                                val tmp = items[pos - 1]
                                items[pos - 1] = items[pos]
                                items[pos] = tmp
                            }
                        },
                        onMoveDown = {
                            val pos = items.indexOfFirst { it.id == item.id }
                            if (pos in 0 until items.size - 1) {
                                val tmp = items[pos + 1]
                                items[pos + 1] = items[pos]
                                items[pos] = tmp
                            }
                        },
                    )
                }
            }
            if (filtered.isEmpty()) {
                BasicText(
                    text = "(no items match)",
                    modifier =
                        Modifier
                            .testTag("todo-empty")
                            .semantics { contentDescription = "no items match the filter" },
                    style = labelStyle,
                )
            }
        }
    }
}

@Composable
private fun TodoRow(
    item: TodoItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .testTag("todo-row-${item.id}")
                .background(Color(0xFF1A1F2A), RoundedCornerShape(6.dp))
                .padding(8.dp),
    ) {
        // Checkbox.
        Box(
            modifier =
                Modifier
                    .testTag("todo-check-${item.id}")
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (item.done) Color(0xFF34D399) else Color(0xFF374151))
                    .clickable { onToggle() }
                    .semantics {
                        role = Role.Checkbox
                        toggleableState = if (item.done) ToggleableState.On else ToggleableState.Off
                        contentDescription = "Toggle done for ${item.title}"
                    },
        )
        // Title — content description carries the stable identity for AT clients.
        BasicText(
            text = item.title,
            modifier =
                Modifier
                    .testTag("todo-title-${item.id}")
                    .semantics { contentDescription = item.title },
            style = labelStyle,
        )
        Spacer(Modifier.weight(1f))
        ComplexButton(
            label = "↑",
            tag = "todo-up-${item.id}",
            onClick = onMoveUp,
            enabled = canMoveUp,
        )
        ComplexButton(
            label = "↓",
            tag = "todo-down-${item.id}",
            onClick = onMoveDown,
            enabled = canMoveDown,
        )
        ComplexButton(
            label = "Delete",
            tag = "todo-delete-${item.id}",
            onClick = onDelete,
        )
    }
}

// ── Auto-ticking progress ─────────────────────────────────────────────────

@Composable
private fun AutoTickerSection() {
    var ticking by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(ticking) {
        if (!ticking) return@LaunchedEffect
        // 100 ms cadence — every tick bumps `value` by ~3 % which drives a
        // partial snapshot containing just the slider node.
        while (ticking) {
            delay(100)
            value = ((value + 0.03f) % 1.0f)
        }
    }
    Section("Auto-ticking progress (high-frequency partials)") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ComplexButton(
                label = if (ticking) "Stop" else "Start",
                tag = "ticker-toggle",
                onClick = { ticking = !ticking },
            )
            BasicText(text = "value=${"%.2f".format(value)}", style = labelStyle)
            Box(
                modifier =
                    Modifier
                        .testTag("ticker-progress")
                        .width(220.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0xFF1F2937))
                        .semantics {
                            contentDescription = "Auto-ticker"
                            progressBarRangeInfo =
                                androidx.compose.ui.semantics.ProgressBarRangeInfo(
                                    current = value,
                                    range = 0f..1f,
                                )
                        },
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(value.coerceIn(0f, 1f))
                            .height(18.dp)
                            .background(Color(0xFF8AB4FF), RoundedCornerShape(9.dp)),
                )
            }
        }
    }
}

// ── Expandable groups ─────────────────────────────────────────────────────

@Composable
private fun ExpandablesSection() {
    var alpha by remember { mutableStateOf(false) }
    var beta by remember { mutableStateOf(false) }
    var gamma by remember { mutableStateOf(true) }
    Section("Expandable groups") {
        Expandable(
            tag = "alpha",
            title = "Group α (settings)",
            expanded = alpha,
            onToggle = { alpha = !alpha },
            bodyItems = listOf("Setting A — toggle me", "Setting B — toggle me"),
        )
        Expandable(
            tag = "beta",
            title = "Group β (advanced)",
            expanded = beta,
            onToggle = { beta = !beta },
            bodyItems = listOf("Advanced option 1", "Advanced option 2", "Advanced option 3"),
        )
        Expandable(
            tag = "gamma",
            title = "Group γ (always shown)",
            expanded = gamma,
            onToggle = { gamma = !gamma },
            bodyItems = listOf("Inside γ — visible by default"),
        )
    }
}

@Composable
private fun Expandable(
    tag: String,
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    bodyItems: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .testTag("expand-$tag")
                    .clickable { onToggle() }
                    .semantics {
                        role = Role.Button
                        contentDescription = if (expanded) "$title (expanded)" else "$title (collapsed)"
                        if (expanded) {
                            collapse {
                                onToggle()
                                true
                            }
                        } else {
                            expand {
                                onToggle()
                                true
                            }
                        }
                    }.background(Color(0xFF1F2937), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            BasicText(
                text = if (expanded) "▼  $title" else "▶  $title",
                style = labelStyle.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        if (expanded) {
            // Pure-text body would not appear in the Tab order, so a screen
            // reader user pressing Tab after expanding the group would never
            // hear its contents. Fold the items into a single keyboard-
            // focusable container with a combined contentDescription so Tab
            // lands on it and Narrator reads the whole body.
            val bodyDescription = "$title contents: " + bodyItems.joinToString(separator = ", ")
            Column(
                modifier =
                    Modifier
                        .testTag("expand-$tag-body")
                        .padding(start = 18.dp, top = 2.dp, bottom = 2.dp)
                        .focusable()
                        .semantics(mergeDescendants = true) {
                            contentDescription = bodyDescription
                        },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (item in bodyItems) {
                    BasicText(text = item, style = labelStyle)
                }
            }
        }
    }
}

// ── Conditional form ──────────────────────────────────────────────────────

@Composable
private fun ConditionalFormSection() {
    var mode by remember { mutableStateOf("basic") }
    var optA by remember { mutableStateOf(false) }
    var optB by remember { mutableStateOf(false) }
    var optC by remember { mutableStateOf(false) }
    Section("Conditional form (subtree replacement)") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (m in listOf("basic", "advanced", "off")) {
                Box(
                    modifier =
                        Modifier
                            .testTag("mode-$m")
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (mode == m) Color(0xFF8AB4FF) else Color(0xFF374151))
                            .clickable { mode = m }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .semantics {
                                role = Role.RadioButton
                                toggleableState = if (mode == m) ToggleableState.On else ToggleableState.Off
                                contentDescription = "Form mode: $m"
                            },
                ) {
                    BasicText(text = m, style = labelStyle)
                }
            }
        }
        when (mode) {
            "basic" -> {
                FormCheckbox("Option A (basic)", "form-a-basic", optA) { optA = !optA }
                FormCheckbox("Option B (basic)", "form-b-basic", optB) { optB = !optB }
            }
            "advanced" -> {
                FormCheckbox("Option A (advanced)", "form-a-advanced", optA) { optA = !optA }
                FormCheckbox("Option B (advanced)", "form-b-advanced", optB) { optB = !optB }
                FormCheckbox("Option C (advanced only)", "form-c-advanced", optC) { optC = !optC }
            }
            "off" -> {
                BasicText(
                    text = "(form disabled)",
                    modifier =
                        Modifier
                            .testTag("form-disabled-msg")
                            .semantics { contentDescription = "form is disabled" },
                    style = labelStyle,
                )
            }
        }
    }
}

@Composable
private fun FormCheckbox(
    label: String,
    tag: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .testTag(tag)
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (checked) Color(0xFF34D399) else Color(0xFF374151))
                    .clickable { onToggle() }
                    .semantics {
                        role = Role.Checkbox
                        toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                        contentDescription = label
                    },
        )
        BasicText(text = label, style = labelStyle)
    }
}

// ── Building blocks ───────────────────────────────────────────────────────

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = TextStyle(color = Color(0xFF9CA3AF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        )
        content()
    }
}

@Composable
private fun ComplexButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val base =
        Modifier
            .testTag(tag)
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) Color(0xFF1F2937) else Color(0xFF11151B))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    // mergeDescendants + contentDescription: label is the accessible name;
    // clickable must stay outside a bare semantics {} that would drop OnClick.
    val mod =
        if (enabled) {
            base
                .clickable { onClick() }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = label
                }
        } else {
            base.semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                this[androidx.compose.ui.semantics.SemanticsProperties.Disabled] = Unit
            }
        }
    Box(modifier = mod) {
        BasicText(
            text = label,
            style =
                TextStyle(
                    color = if (enabled) Color(0xFFE6E6E6) else Color(0xFF6B7280),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
        )
    }
}

private val labelStyle = TextStyle(color = Color(0xFFCBD5E1), fontSize = 12.sp)
