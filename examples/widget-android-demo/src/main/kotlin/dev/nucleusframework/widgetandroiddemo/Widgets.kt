package dev.nucleusframework.widgetandroiddemo

import androidx.compose.runtime.Composable
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
import dev.nucleusframework.widget.NucleusWidget
import dev.nucleusframework.widget.Row
import dev.nucleusframework.widget.Spacer
import dev.nucleusframework.widget.Spacing
import dev.nucleusframework.widget.Switch
import dev.nucleusframework.widget.Text
import dev.nucleusframework.widget.TextSize
import dev.nucleusframework.widget.TextStyle
import dev.nucleusframework.widget.WidgetBackground
import dev.nucleusframework.widget.WidgetColor
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetModifier
import dev.nucleusframework.widget.WidgetSize
import dev.nucleusframework.widget.actionOpenUrl
import dev.nucleusframework.widget.background
import dev.nucleusframework.widget.booleanStateKey
import dev.nucleusframework.widget.clickable
import dev.nucleusframework.widget.currentWidgetState
import dev.nucleusframework.widget.fillMaxHeight
import dev.nucleusframework.widget.fillMaxSize
import dev.nucleusframework.widget.fillMaxWidth
import dev.nucleusframework.widget.intStateKey
import dev.nucleusframework.widget.stringStateKey
import dev.nucleusframework.widget.weight

const val COUNTER_WIDGET = "Counter"
const val TASKS_WIDGET = "Tasks"

val CountKey = intStateKey("count")
val NoteKey = stringStateKey("note")
private val ShowNoteKey = booleanStateKey("showNote")
private val BackgroundKey = stringStateKey("background")

private val Backgrounds =
    listOf(
        Choice("Plain", WidgetBackground.Default.name),
        Choice("Emphasis", WidgetBackground.Emphasis.name),
        Choice("Accent", WidgetBackground.Accent.name),
    )

private val Logo = ImageProvider.appResource("widget/logo.png")

/** The Lab's counter, on Android: lambdas, a switch, a choice, an app image and a deep link. */
class CounterWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent { Counter() }
    }

    @Composable
    private fun Counter() {
        val state = currentWidgetState()
        val count = state[CountKey] ?: 0
        val background =
            WidgetBackground.entries.firstOrNull { it.name == state[BackgroundKey] } ?: WidgetBackground.Default
        val isSmall = LocalSize.current == WidgetSize.Small
        Column(
            modifier = WidgetModifier.fillMaxSize().background(background),
            spacing = Spacing.Small,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Row(spacing = Spacing.Small, verticalAlignment = Alignment.Vertical.CenterVertically) {
                Image(Logo, contentDescription = "Nucleus", size = ImageSize.Icon)
                Text("Counter", style = TextStyle(fontWeight = FontWeight.Bold))
            }
            Spacer(WidgetModifier.fillMaxHeight())
            Text("$count", style = TextStyle(fontSize = TextSize.ExtraLarge, fontWeight = FontWeight.Bold))
            if (!isSmall && state[ShowNoteKey] != false) {
                state[NoteKey]?.let {
                    Text(it, style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle), maxLines = 1)
                }
            }
            Spacer(WidgetModifier.fillMaxHeight())
            Row(spacing = Spacing.Small) {
                if (!isSmall) {
                    Button("Reset", enabled = count != 0, onClick = {
                        state.update {
                            it[CountKey] = 0
                            it[NoteKey] = "Reset from the widget"
                        }
                    })
                }
                Button("+1", onClick = {
                    state.update {
                        it[CountKey] = count + 1
                        it[NoteKey] = "+1 from the widget"
                    }
                })
            }
            if (LocalSize.current == WidgetSize.Large) {
                Switch(ShowNoteKey, title = "Show the last change", defaultValue = true)
                ChoiceField(BackgroundKey, Backgrounds, label = "Background")
            }
            if (!isSmall) {
                Row(modifier = WidgetModifier.clickable(actionOpenUrl("widgetdemo://counter"))) {
                    Text("Open the demo", style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Accent))
                }
            }
        }
    }
}

private val TasksKey = stringStateKey("tasks")
private val DoneKey = stringStateKey("done")
private val NextKey = intStateKey("next")
private val OpenIcon = ImageProvider.appResource("widget/task-open.png")
private val DoneIcon = ImageProvider.appResource("widget/task-done.png")
private val Samples = listOf("Buy milk", "Call Sam", "Water the plants", "Book the dentist", "Pay rent")

/**
 * A task list: rows clickable as a whole (their key is their position, so a click on an outdated
 * list is caught by its signature), weighted labels, app images, a footer pushed down.
 */
class TasksWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent { Tasks() }
    }

    @Composable
    private fun Tasks() {
        val state = currentWidgetState()
        val tasks = state[TasksKey]?.split('\n')?.filter { it.isNotEmpty() } ?: listOf("Buy milk", "Call Sam")
        val done =
            state[DoneKey]
                ?.split('\n')
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()
        Column(modifier = WidgetModifier.fillMaxSize(), spacing = Spacing.Small) {
            Row(modifier = WidgetModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(
                    "Tasks",
                    modifier = WidgetModifier.weight(1),
                    style = TextStyle(fontSize = TextSize.Large, fontWeight = FontWeight.Bold),
                )
                Text("${done.count { it in tasks }}/${tasks.size}", style = TextStyle(color = WidgetColor.Subtle))
            }
            for (task in tasks) {
                val isDone = task in done
                Row(
                    modifier =
                        WidgetModifier
                            .fillMaxWidth()
                            .background(WidgetBackground.Emphasis)
                            .clickable {
                                state.update {
                                    it[DoneKey] = (if (isDone) done - task else done + task).joinToString("\n")
                                }
                            },
                    spacing = Spacing.Small,
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    Image(
                        if (isDone) DoneIcon else OpenIcon,
                        contentDescription = if (isDone) "Done" else "Open",
                        size = ImageSize.Icon,
                    )
                    Text(
                        task,
                        modifier = WidgetModifier.weight(1),
                        style = TextStyle(color = if (isDone) WidgetColor.Subtle else WidgetColor.Default),
                        maxLines = 1,
                    )
                }
            }
            Spacer(WidgetModifier.fillMaxHeight())
            Row(spacing = Spacing.Small) {
                Button("Add", onClick = {
                    val next = state[NextKey] ?: 0
                    state.update {
                        it[TasksKey] = (tasks + "${Samples[next % Samples.size]} #${next + 1}").joinToString("\n")
                        it[NextKey] = next + 1
                    }
                })
                Button("Clear done", enabled = done.isNotEmpty(), onClick = {
                    state.update {
                        it[TasksKey] = tasks.filterNot { t -> t in done }.joinToString("\n")
                        it[DoneKey] = ""
                    }
                })
            }
        }
    }
}
