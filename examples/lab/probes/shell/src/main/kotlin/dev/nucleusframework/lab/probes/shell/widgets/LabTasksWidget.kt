package dev.nucleusframework.lab.probes.shell.widgets

import androidx.compose.runtime.Composable
import dev.nucleusframework.widget.ActionCallback
import dev.nucleusframework.widget.ActionParameters
import dev.nucleusframework.widget.Alignment
import dev.nucleusframework.widget.Button
import dev.nucleusframework.widget.Choice
import dev.nucleusframework.widget.ChoiceField
import dev.nucleusframework.widget.Column
import dev.nucleusframework.widget.FontWeight
import dev.nucleusframework.widget.Image
import dev.nucleusframework.widget.ImageProvider
import dev.nucleusframework.widget.ImageSize
import dev.nucleusframework.widget.LocalSize
import dev.nucleusframework.widget.MutableWidgetState
import dev.nucleusframework.widget.NucleusWidget
import dev.nucleusframework.widget.Row
import dev.nucleusframework.widget.Spacer
import dev.nucleusframework.widget.Spacing
import dev.nucleusframework.widget.Switch
import dev.nucleusframework.widget.Text
import dev.nucleusframework.widget.TextField
import dev.nucleusframework.widget.TextSize
import dev.nucleusframework.widget.TextStyle
import dev.nucleusframework.widget.WidgetColor
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetModifier
import dev.nucleusframework.widget.WidgetSize
import dev.nucleusframework.widget.WidgetState
import dev.nucleusframework.widget.WidgetStateHandle
import dev.nucleusframework.widget.actionFinishCustomization
import dev.nucleusframework.widget.actionParametersOf
import dev.nucleusframework.widget.actionRunCallback
import dev.nucleusframework.widget.booleanStateKey
import dev.nucleusframework.widget.clickable
import dev.nucleusframework.widget.currentWidgetState
import dev.nucleusframework.widget.fillMaxHeight
import dev.nucleusframework.widget.fillMaxWidth
import dev.nucleusframework.widget.isCustomizing
import dev.nucleusframework.widget.stringStateKey
import dev.nucleusframework.widget.updateWidgetState
import dev.nucleusframework.widget.weight

/** Definition id shared with `windows { widgets { widget(…) } }` in the Lab's build script. */
const val LAB_TASKS_WIDGET: String = "LabTasks"

/** One task of the list. */
data class LabTask(
    val title: String,
    val isDone: Boolean,
)

/**
 * The task list, kept in one string state key: a line per task, `x ` or `- ` then the title.
 * Titles come from a single-line field, so they never hold a line break.
 */
object LabTasks {
    internal val ListKey = stringStateKey("tasks")
    internal val DraftKey = stringStateKey("draft")
    internal val FilterKey = stringStateKey("filter")

    // Set from the customization UI.
    internal val ListTitleKey = stringStateKey("listTitle")
    internal val HideDoneKey = booleanStateKey("hideDone")
    internal val OrderKey = stringStateKey("order")

    /** The tasks stored in [state]. */
    fun read(state: WidgetState): List<LabTask> = decode(state[ListKey])

    /** Replaces the tasks of [state]. */
    fun write(
        state: MutableWidgetState,
        tasks: List<LabTask>,
    ) {
        state[ListKey] = tasks.joinToString("\n") { (if (it.isDone) "x " else "- ") + it.title }
    }

    /** Appends [title] to the tasks of [state]; blank titles are ignored. */
    fun add(
        state: MutableWidgetState,
        title: String,
    ) {
        val trimmed = title.trim().replace('\n', ' ')
        if (trimmed.isNotEmpty()) write(state, decode(state[ListKey]) + LabTask(trimmed, isDone = false))
    }

    internal fun decode(encoded: String?): List<LabTask> =
        encoded
            .orEmpty()
            .lineSequence()
            .filter { it.length > 2 }
            .map { LabTask(it.substring(2), isDone = it[0] == 'x') }
            .toList()
}

private enum class Filter(
    val title: String,
) {
    All("All"),
    Active("To do"),
    Done("Done"),
}

private val Orders =
    listOf(
        Choice("As added", "added"),
        Choice("To do first", "todoFirst"),
    )

/** Flips the task at [Index] (its position in the whole list): runs from a click on its row. */
private object ToggleTask : ActionCallback {
    val Index = ActionParameters.Key<Int>("index")

    override suspend fun onAction(
        id: WidgetId,
        parameters: ActionParameters,
    ) {
        val index = parameters[Index] ?: return
        // Changes the state this action runs on: the widget is rendered from it right after.
        updateWidgetState(id) { state ->
            val tasks = LabTasks.read(state).toMutableList()
            if (index in tasks.indices) {
                tasks[index] = tasks[index].let { it.copy(isDone = !it.isDone) }
                LabTasks.write(state, tasks)
            }
        }
    }
}

private val OpenIcon = ImageProvider.appResource("widget/task-open.png")
private val DoneIcon = ImageProvider.appResource("widget/task-done.png")

/**
 * A to-do list on the widgets board, laid out like the board's own widgets: the host's header
 * already names it, so the card opens on a status line, then the tasks, then one place to add.
 * Small: the next task and Mark done. Medium: the first tasks and the add field. Large: filter
 * tabs and more tasks. Customizable (list name, hide completed tasks, order); the Lab adds tasks
 * from the app too (probe `shell.windows-widgets`).
 */
class LabTasksWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            if (isCustomizing()) Settings() else Tasks()
        }
    }

    @Composable
    private fun Settings() {
        Column(spacing = Spacing.Default) {
            TextField(LabTasks.ListTitleKey, label = "List name", placeholder = "Optional", maxLength = 24)
            Switch(LabTasks.HideDoneKey, title = "Hide completed tasks")
            ChoiceField(LabTasks.OrderKey, Orders, label = "Order")
            Button("Save", onClick = actionFinishCustomization())
        }
    }

    @Composable
    private fun Tasks() {
        val state = currentWidgetState()
        val tasks = LabTasks.decode(state[LabTasks.ListKey])
        val size = LocalSize.current
        Column(modifier = WidgetModifier.fillMaxHeight(), spacing = Spacing.Small) {
            state[LabTasks.ListTitleKey]?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = TextStyle(fontWeight = FontWeight.Bold), maxLines = 1)
            }
            Status(state, tasks, canClear = size != WidgetSize.Small)
            if (size == WidgetSize.Small) NextTask(tasks) else TaskList(state, tasks, size)
        }
    }

    /** "3 of 5 left" and, when something is done, the action that clears it. */
    @Composable
    private fun Status(
        state: WidgetStateHandle,
        tasks: List<LabTask>,
        canClear: Boolean,
    ) {
        val left = tasks.count { !it.isDone }
        Row(modifier = WidgetModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                when {
                    tasks.isEmpty() -> "No tasks"
                    left == 0 -> "All done"
                    else -> "$left of ${tasks.size} left"
                },
                modifier = WidgetModifier.weight(1),
                style =
                    TextStyle(
                        fontSize = TextSize.Small,
                        color = if (tasks.isNotEmpty() && left == 0) WidgetColor.Good else WidgetColor.Subtle,
                    ),
            )
            if (canClear && left < tasks.size) {
                Row(
                    modifier =
                        WidgetModifier.clickable {
                            state.update { s -> LabTasks.write(s, tasks.filterNot(LabTask::isDone)) }
                        },
                ) {
                    Text("Clear completed", style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Accent))
                }
            }
        }
    }

    @Composable
    private fun NextTask(tasks: List<LabTask>) {
        val next = tasks.indexOfFirst { !it.isDone }
        if (next < 0) return
        Text(
            tasks[next].title,
            style = TextStyle(fontSize = TextSize.Medium, fontWeight = FontWeight.Bold),
            maxLines = 3,
        )
        Spacer(WidgetModifier.fillMaxHeight())
        Button("Mark done", onClick = actionRunCallback(ToggleTask, actionParametersOf(ToggleTask.Index to next)))
    }

    @Composable
    private fun TaskList(
        state: WidgetStateHandle,
        tasks: List<LabTask>,
        size: WidgetSize,
    ) {
        val isLarge = size == WidgetSize.Large
        val filter =
            if (isLarge) {
                Filter.entries.firstOrNull {
                    it.name == state[LabTasks.FilterKey]
                } ?: Filter.All
            } else {
                Filter.All
            }
        if (isLarge) Tabs(state, filter)

        // Indices into the whole list, so a click flips the right task whatever is shown.
        val shown =
            tasks.indices
                .filter { i ->
                    when (filter) {
                        Filter.All -> state[LabTasks.HideDoneKey] != true || !tasks[i].isDone
                        Filter.Active -> !tasks[i].isDone
                        Filter.Done -> tasks[i].isDone
                    }
                }.let { indices ->
                    if (state[LabTasks.OrderKey] == "todoFirst") indices.sortedBy { tasks[it].isDone } else indices
                }
        val capacity = if (isLarge) LARGE_ROWS else MEDIUM_ROWS
        Column(spacing = Spacing.Default) {
            if (shown.isEmpty()) {
                Text(
                    if (tasks.isEmpty()) "Add your first task below." else "Nothing here.",
                    style = TextStyle(color = WidgetColor.Subtle),
                )
            }
            for (index in shown.take(capacity)) TaskRow(index, tasks[index])
            if (shown.size > capacity) {
                Text(
                    "${shown.size - capacity} more",
                    style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle),
                )
            }
        }
        Spacer(WidgetModifier.fillMaxHeight())
        AddTask(state)
    }

    /** Text tabs, the selected one bold: lighter than a row of buttons, and never disabled-looking. */
    @Composable
    private fun Tabs(
        state: WidgetStateHandle,
        current: Filter,
    ) {
        Row(spacing = Spacing.Medium) {
            for (filter in Filter.entries) {
                val isSelected = filter == current
                Row(modifier = WidgetModifier.clickable { state.update { it[LabTasks.FilterKey] = filter.name } }) {
                    Text(
                        filter.title,
                        style =
                            TextStyle(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) WidgetColor.Default else WidgetColor.Subtle,
                            ),
                    )
                }
            }
        }
    }

    @Composable
    private fun TaskRow(
        index: Int,
        task: LabTask,
    ) {
        Row(
            modifier =
                WidgetModifier
                    .fillMaxWidth()
                    .clickable(actionRunCallback(ToggleTask, actionParametersOf(ToggleTask.Index to index))),
            spacing = Spacing.Small,
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Image(
                if (task.isDone) DoneIcon else OpenIcon,
                contentDescription = if (task.isDone) "Completed" else "Not completed",
                size = ImageSize.Icon,
            )
            Text(
                task.title,
                modifier = WidgetModifier.weight(1),
                style = TextStyle(color = if (task.isDone) WidgetColor.Subtle else WidgetColor.Default),
                maxLines = 1,
            )
        }
    }

    @Composable
    private fun AddTask(state: WidgetStateHandle) {
        Row(spacing = Spacing.Small, verticalAlignment = Alignment.Vertical.CenterVertically) {
            TextField(
                LabTasks.DraftKey,
                modifier = WidgetModifier.weight(1),
                placeholder = "Add a task",
                maxLength = MAX_TITLE,
            )
            // The field's value is in the state when this runs: the host submits it with the click.
            Button("Add", onClick = {
                state.update {
                    LabTasks.add(it, it[LabTasks.DraftKey].orEmpty())
                    it.remove(LabTasks.DraftKey)
                }
            })
        }
    }

    private companion object {
        const val MEDIUM_ROWS = 4
        const val LARGE_ROWS = 8
        const val MAX_TITLE = 60
    }
}
